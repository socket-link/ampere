package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeAskHuman(
    context: ExecutionContext.NoChanges,
): ExecutionOutcome.NoChanges {
    val executionStartTimestamp = Clock.System.now()

    // iOS does not support interactive human input via CLI
    // Return a failure indicating this capability is not available on iOS
    return ExecutionOutcome.NoChanges.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = executionStartTimestamp,
        executionEndTimestamp = Clock.System.now(),
        message = "Human input is not supported on iOS platform",
    )
}
