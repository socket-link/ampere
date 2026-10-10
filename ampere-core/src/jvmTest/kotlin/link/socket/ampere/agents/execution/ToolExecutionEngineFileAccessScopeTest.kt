package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.FileAccessScope
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
 * AMPR-414: the file access scope the agent stamped has to survive enrichment.
 *
 * Exactly the AMPR-351 problem, one field over: `CodeParams.CodeWriting` —
 * the strategy that turns an intent into "files to write", and so the only
 * reason `write_code_file` ever has a path to gate — builds a *fresh*
 * [ExecutionRequest] for its generated parameters. A scope stamped before
 * enrichment is gone by dispatch, which would leave the gate at the tool
 * looking at null and enforcing nothing. The engine is the single dispatch
 * funnel, so re-applying it there is what makes the gate real.
 */
class ToolExecutionEngineFileAccessScopeTest {

    private val kotlinOnly = FileAccessScope(
        readPatterns = setOf("**/*"),
        writePatterns = setOf("**/*.kt"),
        forbiddenPatterns = setOf("**/build/**"),
    )

    @Test
    fun `the scope reaches the tool through a strategy that rebuilds the request`() = runTest {
        val seen = mutableListOf<FileAccessScope?>()
        val engine = engineWith(strategy = rebuildingStrategy)

        engine.execute(recordingTool(seen), request(fileAccessScope = kotlinOnly))

        assertEquals(kotlinOnly, seen.single())
    }

    @Test
    fun `the scope reaches the tool with no strategy registered`() = runTest {
        val seen = mutableListOf<FileAccessScope?>()
        val engine = engineWith(strategy = null)

        engine.execute(recordingTool(seen), request(fileAccessScope = kotlinOnly))

        assertEquals(kotlinOnly, seen.single())
    }

    @Test
    fun `a request with no scope leaves the tool unconstrained`() = runTest {
        val seen = mutableListOf<FileAccessScope?>()
        val engine = engineWith(strategy = rebuildingStrategy)

        engine.execute(recordingTool(seen), request(fileAccessScope = null))

        assertEquals(1, seen.size)
        assertNull(
            seen.single(),
            "null is 'nothing narrowed this call', the same thing a null availableTools means",
        )
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

    private fun recordingTool(seen: MutableList<FileAccessScope?>) =
        FunctionTool<ExecutionContext>(
            id = TOOL_ID,
            name = "Record File Access Scope",
            description = "Records the file access scope of the request it was dispatched with",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { executionRequest ->
                seen += executionRequest.fileAccessScope
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

    private fun engineWith(strategy: ParameterStrategy?): ToolExecutionEngine {
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
        ).also { engine ->
            strategy?.let { engine.registerStrategy(TOOL_ID, it) }
        }
    }

    private fun request(
        fileAccessScope: FileAccessScope?,
    ): ExecutionRequest<ExecutionContext.NoChanges> {
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
            fileAccessScope = fileAccessScope,
        )
    }

    private companion object {
        const val TOOL_ID = "record-file-access-scope"
    }
}
