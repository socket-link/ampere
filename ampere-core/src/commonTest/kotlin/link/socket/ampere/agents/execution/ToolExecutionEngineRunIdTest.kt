package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * AMPR-351: the run id the engine was built with has to survive all the way to the tool's
 * execution function, which is the only place a run-attributable tool (ToolAskHuman) can
 * read it. The hard part is [ParameterStrategy]: every real one builds a *fresh*
 * [ExecutionRequest] for its generated parameters rather than copying the original, so a
 * run stamped before enrichment would be thrown away again before dispatch.
 */
class ToolExecutionEngineRunIdTest {

    @Test
    fun `run id reaches the tool through a strategy that rebuilds the request`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = engineWith(runId = "arc-run-1", strategy = rebuildingStrategy)

        engine.execute(recordingTool(seen), request())

        assertEquals("arc-run-1", seen.single())
    }

    @Test
    fun `run id reaches the tool with no strategy registered`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = engineWith(runId = "arc-run-1", strategy = null)

        engine.execute(recordingTool(seen), request())

        assertEquals("arc-run-1", seen.single())
    }

    @Test
    fun `the run named by the request wins over the engine's own`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = engineWith(runId = "engine-run", strategy = rebuildingStrategy)

        engine.execute(recordingTool(seen), request(runId = "caller-run"))

        assertEquals("caller-run", seen.single())
    }

    @Test
    fun `a runless engine leaves the dispatched request unattributed`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = engineWith(runId = null, strategy = rebuildingStrategy)

        engine.execute(recordingTool(seen), request())

        assertEquals(1, seen.size)
        assertNull(seen.single())
    }

    /** Mirrors the real strategies: a brand-new request, carrying only what it chose to copy. */
    private val rebuildingStrategy = object : ParameterStrategy {
        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = "Generate parameters for ${tool.id}: $intent"

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> = ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = originalRequest.context.executorId,
                ticket = originalRequest.context.ticket,
                task = originalRequest.context.task,
                instructions = "enriched: ${originalRequest.context.instructions}",
            ),
            constraints = originalRequest.constraints,
        )
    }

    private fun recordingTool(seen: MutableList<String?>) =
        FunctionTool<ExecutionContext>(
            id = "record-run-id",
            name = "Record Run Id",
            description = "Records the run id of the request it was dispatched with",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { executionRequest ->
                seen += executionRequest.runId
                val now = Clock.System.now()
                ExecutionOutcome.NoChanges.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now,
                    message = "recorded",
                )
            },
        )

    private fun engineWith(
        runId: String?,
        strategy: ParameterStrategy?,
    ): ToolExecutionEngine {
        val executor = FunctionExecutor.create()
        val llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { "{}" },
            ),
        )
        return ToolExecutionEngine(
            llmService = llmService,
            executor = executor,
            executorId = executor.id,
            runId = runId,
        ).also { engine ->
            strategy?.let { engine.registerStrategy("record-run-id", it) }
        }
    }

    private fun request(runId: String? = null): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = "agent-1",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Test ticket",
                    description = "Test ticket description",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "agent-1",
                    createdByAgentId = "agent-1",
                    createdAt = now,
                    updatedAt = now,
                    dueDate = null,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Do the thing",
                ),
                instructions = "Do the thing",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
            runId = runId,
        )
    }
}
