package link.socket.ampere.agents.execution.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
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

/**
 * AMPR-420: a tool that ran and had nothing to report ends in [ExecutionStatus.Blank].
 *
 * The executor protocol's terminal statuses were [ExecutionStatus.Completed], which carries
 * a success, and [ExecutionStatus.Failed], so a tool that returned a blank outcome could only
 * be reported as a failure: [FunctionExecutor] answered "Unexpected outcome type" for a tool
 * that had done nothing wrong. The engine reads the new status back as an explicit
 * `ExecutionOutcome.Blank`; what it does with one is `ToolExecutionEngineDispatchTest`.
 */
class FunctionExecutorBlankOutcomeTest {

    @Test
    fun `a tool that returns a blank outcome ends in a Blank status`() = runTest {
        val statuses = FunctionExecutor.create().execute(testRequest(), blankTool(Outcome.blank)).toList()

        val last = assertIs<ExecutionStatus.Blank>(statuses.last())
        assertTrue(last.isClosed, "Blank is a terminal status")
        assertEquals(FunctionExecutor.DEFAULT_ID, last.executorId)
    }

    @Test
    fun `a tool that returns a blank execution outcome ends in a Blank status too`() = runTest {
        val statuses = FunctionExecutor.create().execute(testRequest(), blankTool(ExecutionOutcome.blank)).toList()

        assertIs<ExecutionStatus.Blank>(statuses.last())
    }

    @Test
    fun `a blank is neither a completion nor a failure`() = runTest {
        val statuses = FunctionExecutor.create().execute(testRequest(), blankTool(Outcome.blank)).toList()

        assertTrue(
            statuses.none { it is ExecutionStatus.Completed || it is ExecutionStatus.Failed },
            "a tool with nothing to report neither succeeded nor failed: $statuses",
        )
    }

    private fun blankTool(result: Outcome) = FunctionTool<ExecutionContext>(
        id = "blank-tool",
        name = "blank-tool",
        description = "Ran and had nothing to report",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { result },
    )

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
            actorId = "test-agent",
            executorId = "test-executor",
            ticket = ticket,
            task = task,
            instructions = "Test instructions",
            knowledgeFromPastMemory = emptyList(),
        )
        return ExecutionRequest(context = context, constraints = ExecutionConstraints())
    }
}
