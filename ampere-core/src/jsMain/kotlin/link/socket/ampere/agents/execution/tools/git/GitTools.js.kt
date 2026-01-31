package link.socket.ampere.agents.execution.tools.git

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

/**
 * JS implementation for Git operations.
 *
 * Git operations are not supported in browser environments as they require
 * command-line tools (git, gh) that are not available in JavaScript.
 *
 * Agents running in the browser should delegate Git operations to a
 * server-side component or use API-based alternatives.
 */
actual suspend fun executeGitOperation(
    context: ExecutionContext.GitOperation,
): ExecutionOutcome.GitOperation {
    val startTime = Clock.System.now()
    val endTime = Clock.System.now()

    return ExecutionOutcome.GitOperation.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = startTime,
        executionEndTimestamp = endTime,
        error = ExecutionError(
            type = ExecutionError.Type.TOOL_UNAVAILABLE,
            message = "Git operations are not supported in browser environments. " +
                "These operations require command-line tools that are not available in JavaScript.",
            details = "Consider using a server-side component or API-based alternatives for Git operations.",
            isRetryable = false,
        ),
        partialResponse = null,
    )
}
