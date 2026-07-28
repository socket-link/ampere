package link.socket.ampere.agents.execution.executor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
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
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool

/** AMPR-186 task 4.2 validation: effect-free Link execution. */
class NoOpExecutorTest {

    @Test
    fun `NoOpExecutor completes without dispatching the tool's real side effect`() = runTest {
        var dispatched = false
        val notifyingTool = FunctionTool<ExecutionContext>(
            id = "notify",
            name = "notify-human",
            description = "Would send a real notification if executed",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = {
                dispatched = true
                ExecutionOutcome.blank
            },
        )

        val executor = NoOpExecutor()
        val statuses = executor.execute(testRequest(), notifyingTool).toList()

        assertFalse(dispatched, "NoOpExecutor must never invoke the tool's real execute function")
        assertIs<ExecutionStatus.Completed>(statuses.last())
        assertIs<ExecutionOutcome.NoChanges.Success>(statuses.last().let { (it as ExecutionStatus.Completed).result })
    }

    @Test
    fun `NoOpExecutor performHealthCheck reports available`() = runTest {
        val result = NoOpExecutor().performHealthCheck()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isAvailable)
    }

    private fun testRequest(): ExecutionRequest<ExecutionContext> {
        val now = Clock.System.now()
        val ticket = Ticket(
            id = "ticket-1",
            title = "Test Ticket",
            description = "Test ticket",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = "test-agent",
            createdByAgentId = "test-pm-agent",
            createdAt = now,
            updatedAt = now,
        )
        val task = Task.CodeChange(
            id = "task-1",
            description = "Test task",
            status = TaskStatus.Pending,
            assignedTo = null,
        )
        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = ticket,
            task = task,
            instructions = "Test instructions",
            knowledgeFromPastMemory = emptyList(),
        )
        return ExecutionRequest(context = context, constraints = ExecutionConstraints())
    }
}
