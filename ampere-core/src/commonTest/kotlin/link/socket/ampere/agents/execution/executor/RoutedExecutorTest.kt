package link.socket.ampere.agents.execution.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.SeatTool

/**
 * [RoutedExecutor] (AMPR-491): a bound tool goes to its executor, by bare or seat-namespaced
 * id; everything else goes to the default; the performer named is the executor that ran the
 * tool; health folds.
 *
 * `Executor` is sealed, so the executors here are the shipped ones: a [NoOpExecutor] under its
 * own id stands in for a harness — its outcome names it and says the tool was not dispatched —
 * and a [FunctionExecutor] is the default that runs a tool's own function.
 */
class RoutedExecutorTest {

    private val harness = NoOpExecutor(id = "harness")
    private val default = FunctionExecutor.create()
    private val routed = RoutedExecutor(default = default, bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness))

    @Test
    fun `a tool bound by its bare id runs on the bound executor`() = runTest {
        val outcome = routed.execute(request(), ToolHarnessCodeChange()).last()

        val completed = assertIs<ExecutionStatus.Completed>(outcome)
        assertEquals("harness", completed.executorId)
        assertEquals("harness", completed.result.executorId)
        val message = assertIs<ExecutionOutcome.NoChanges.Success>(completed.result).message
        assertTrue(message.contains("Skipped by NoOpExecutor"), message)
    }

    @Test
    fun `a seat namespaced id finds the binding on its bare id`() = runTest {
        val seatTool = ToolHarnessCodeChange().copy(id = SeatTool(RoleId("scout"), HARNESS_CODE_CHANGE_TOOL_ID).id)

        val outcome = routed.execute(request(), seatTool).last()

        assertEquals("harness", assertIs<ExecutionStatus.Completed>(outcome).executorId)
        assertEquals("harness", routed.performerFor(seatTool))
    }

    @Test
    fun `an exact seat namespaced binding wins over the bare one`() = runTest {
        val scoutsOwn = NoOpExecutor(id = "scouts-harness")
        val routed = RoutedExecutor(
            default = default,
            bindings = mapOf(
                HARNESS_CODE_CHANGE_TOOL_ID to harness,
                SeatTool(RoleId("scout"), HARNESS_CODE_CHANGE_TOOL_ID).id to scoutsOwn,
            ),
        )
        val scoutTool = ToolHarnessCodeChange().copy(id = SeatTool(RoleId("scout"), HARNESS_CODE_CHANGE_TOOL_ID).id)
        val inspectorTool = ToolHarnessCodeChange()
            .copy(id = SeatTool(RoleId("inspector"), HARNESS_CODE_CHANGE_TOOL_ID).id)

        assertEquals("scouts-harness", routed.performerFor(scoutTool))
        assertEquals("harness", routed.performerFor(inspectorTool))
    }

    @Test
    fun `an unbound tool runs on the default and the default is the performer`() = runTest {
        var ran = false
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "search",
            name = "Search",
            description = "searches",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { executionRequest ->
                ran = true
                ExecutionOutcome.NoChanges.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "found it",
                )
            },
        )

        val outcome = routed.execute(request(), tool).last()

        assertTrue(ran, "the default ran the tool's own function")
        assertEquals(FunctionExecutor.DEFAULT_ID, assertIs<ExecutionStatus.Completed>(outcome).executorId)
        assertEquals(FunctionExecutor.DEFAULT_ID, routed.performerFor(tool))
    }

    @Test
    fun `the router never names itself as the performer`() {
        assertEquals("routed", routed.id)
        assertEquals("harness", routed.performerFor(ToolHarnessCodeChange()))
        assertEquals(default.capabilities, routed.capabilities)
    }

    @Test
    fun `health folds the default's and every binding's and names an unavailable binding by tool id`() = runTest {
        val unavailable = McpExecutor(id = "mcp-less", displayName = "No manager", capabilities = default.capabilities)
        val routed = RoutedExecutor(
            default = default,
            bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness, "notify" to unavailable),
        )

        val health = routed.performHealthCheck().getOrThrow()

        assertFalse(health.isAvailable)
        assertTrue(
            health.issues.any { it.startsWith("notify (mcp-less): ") && it.contains("ServerManager not configured") },
            "the issue names the tool and the executor; got ${health.issues}",
        )
        val allUp = this@RoutedExecutorTest.routed.performHealthCheck().getOrThrow()
        assertTrue(allUp.isAvailable, "all available folds to available")
    }

    @Test
    fun `bareIdOf strips one seat and leaves a bare id alone`() {
        assertEquals("harness_code_change", RoutedExecutor.bareIdOf("scout/harness_code_change"))
        assertEquals("harness_code_change", RoutedExecutor.bareIdOf("harness_code_change"))
        assertEquals("issues/create", RoutedExecutor.bareIdOf("github/issues/create"))
    }

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
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
                instructions = "Do the thing",
            ),
        )
    }
}
