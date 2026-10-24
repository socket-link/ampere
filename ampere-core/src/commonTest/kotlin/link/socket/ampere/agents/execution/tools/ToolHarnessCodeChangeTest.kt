package link.socket.ampere.agents.execution.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * `harness_code_change` (AMPR-492): the tool a planner nominates to reach a harness has no
 * strategy and no schema, and run by anything but a bound code executor it says so.
 */
class ToolHarnessCodeChangeTest {

    @Test
    fun `the tool declares no strategy and no schema so the engine makes no model call before dispatch`() {
        val tool = ToolHarnessCodeChange()

        assertEquals(HARNESS_CODE_CHANGE_TOOL_ID, tool.id)
        assertNull(tool.parameterStrategy)
        assertNull(tool.argumentSchema)
        assertNull(tool.plugManifest)
        assertEquals(AgentActionAutonomy.FULLY_AUTONOMOUS, tool.requiredAgentAutonomy)
    }

    @Test
    fun `run by a plain function executor the tool fails saying no code executor is bound`() = runTest {
        val outcome = FunctionExecutor.create().execute(request(), ToolHarnessCodeChange()).last()

        val failed = assertIs<ExecutionStatus.Failed>(outcome)
        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.TOOL_UNAVAILABLE, failure.error.type)
        assertTrue(
            failure.error.message.contains("No code executor is bound for harness_code_change"),
            failure.error.message,
        )
        assertEquals(emptyList(), failure.partiallyChangedFiles, "nothing was touched")
        assertEquals(FunctionExecutor.DEFAULT_ID, failure.executorId, "attributed to the executor that ran it")
    }

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = "agent-1",
                executorId = FunctionExecutor.DEFAULT_ID,
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
                task = Task.CodeChange(id = "task-1", status = TaskStatus.Pending, description = "Add a greeting"),
                instructions = "Add a greeting",
            ),
        )
    }
}
