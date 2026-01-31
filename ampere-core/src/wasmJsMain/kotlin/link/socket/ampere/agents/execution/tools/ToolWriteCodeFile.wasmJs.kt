package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged {
    val executionStartTimestamp = Clock.System.now()

    // Browser JS does not have filesystem access for writing files
    // Return a failure indicating this capability is not available
    return ExecutionOutcome.CodeChanged.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = executionStartTimestamp,
        executionEndTimestamp = Clock.System.now(),
        partiallyChangedFiles = emptyList(),
        error = ExecutionError(
            type = ExecutionError.Type.TOOL_UNAVAILABLE,
            message = "File system write access is not supported in browser environments",
            details = "Writing code files requires filesystem access which is not available in JavaScript browsers",
            isRetryable = false,
        ),
    )
}
