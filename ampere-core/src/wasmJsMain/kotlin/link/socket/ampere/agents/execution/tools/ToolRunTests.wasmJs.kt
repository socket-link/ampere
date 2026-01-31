package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeRunTests(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading {
    val executionStartTimestamp = Clock.System.now()

    // Browser JS does not support running shell commands for test execution
    // Return a failure indicating this capability is not available
    return ExecutionOutcome.CodeReading.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = executionStartTimestamp,
        executionEndTimestamp = Clock.System.now(),
        error = ExecutionError(
            type = ExecutionError.Type.TOOL_UNAVAILABLE,
            message = "Test execution is not supported in browser environments",
            details = "Running tests requires shell access which is not available in JavaScript browsers",
            isRetryable = false,
        ),
    )
}
