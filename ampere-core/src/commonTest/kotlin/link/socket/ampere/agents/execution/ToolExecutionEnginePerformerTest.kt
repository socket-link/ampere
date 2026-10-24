package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import link.socket.ampere.agents.execution.executor.ExecutorCapabilities
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.executor.InstrumentedExecutor
import link.socket.ampere.agents.execution.executor.RoutedExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * The engine names the performer through `Executor.performerFor` (AMPR-491): behind a
 * [RoutedExecutor], the context a tool is dispatched with and the outcome it returns both name
 * the executor the tool was routed to, never the router. The tool-event pair's `executorId` is
 * asserted in `jvmTest`'s hosted-run proof, which has a door.
 */
class ToolExecutionEnginePerformerTest {

    @Test
    fun `a routed tool is dispatched with the bound executor stamped as its performer`() = runTest {
        val seen = mutableListOf<String>()
        val boundTool = recordingTool("harness_code_change", seen)
        val plainTool = recordingTool("search", seen)
        val harness = InstrumentedExecutor(
            id = "harness",
            delegateExecutor = FunctionExecutor(
                id = "harness",
                displayName = "Harness",
                capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            ),
        )
        val engine = engine(
            RoutedExecutor(default = FunctionExecutor.create(), bindings = mapOf("harness_code_change" to harness)),
        )

        val bound = engine.execute(boundTool, request())
        val plain = engine.execute(plainTool, request())

        assertEquals(
            listOf("harness", FunctionExecutor.DEFAULT_ID),
            seen,
            "each tool's context names the executor that ran it",
        )
        assertEquals("harness", assertIs<ExecutionOutcome.NoChanges.Success>(bound).executorId)
        assertEquals(FunctionExecutor.DEFAULT_ID, assertIs<ExecutionOutcome.NoChanges.Success>(plain).executorId)
        assertEquals(1, harness.executionCount)
    }

    @Test
    fun `a refusal before dispatch is attributed to the executor the tool was headed for`() = runTest {
        val engine = engine(
            RoutedExecutor(
                default = FunctionExecutor.create(),
                bindings = mapOf("harness_code_change" to InstrumentedExecutor(id = "harness")),
            ),
        )

        val refused = engine.execute(recordingTool("harness_code_change", mutableListOf()), request(instructions = ""))

        assertEquals("harness", assertIs<ExecutionOutcome.NoChanges.Failure>(refused).executorId)
    }

    private fun engine(executor: RoutedExecutor) = ToolExecutionEngine(
        llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { "{}" },
            ),
        ),
        executor = executor,
        actorId = "agent-1",
    )

    private fun recordingTool(id: String, seen: MutableList<String>) = FunctionTool<ExecutionContext.NoChanges>(
        id = id,
        name = "Tool $id",
        description = "records the executor its context names",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { executionRequest ->
            seen += executionRequest.context.executorId
            ExecutionOutcome.NoChanges.Success(
                executorId = executionRequest.context.executorId,
                ticketId = executionRequest.context.ticket.id,
                taskId = executionRequest.context.task.id,
                executionStartTimestamp = Clock.System.now(),
                executionEndTimestamp = Clock.System.now(),
                message = "done",
            )
        },
    )

    private fun request(instructions: String = "Do the thing"): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = "agent-1",
                executorId = "routed",
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
                ),
                task = Task.CodeChange(id = "task-1", status = TaskStatus.Pending, description = "Do the thing"),
                instructions = instructions,
            ),
        )
    }
}
