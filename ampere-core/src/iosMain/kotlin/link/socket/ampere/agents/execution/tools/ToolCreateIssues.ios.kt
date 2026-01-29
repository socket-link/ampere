package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeCreateIssues(
    context: ExecutionContext.IssueManagement,
): ExecutionOutcome.IssueManagement {
    val startTime = Clock.System.now()

    // iOS does not support GitHub CLI operations
    // Return a failure indicating this capability is not available on iOS
    return ExecutionOutcome.IssueManagement.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = startTime,
        executionEndTimestamp = Clock.System.now(),
        error = ExecutionError(
            type = ExecutionError.Type.TOOL_UNAVAILABLE,
            message = "Issue creation is not supported on iOS platform",
            details = "GitHub CLI operations require shell access which is not available on iOS",
            isRetryable = false,
        ),
        partialResponse = null,
    )
}
