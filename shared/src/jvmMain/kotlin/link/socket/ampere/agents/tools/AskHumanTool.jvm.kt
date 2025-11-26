package link.socket.ampere.agents.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeAskHuman(
    context: ExecutionContext.NoChanges,
): ExecutionOutcome.NoChanges {
    val executionStartTimestamp = Clock.System.now()

    println("Human intervention required")

    // TODO: Handle allowing human intervention
    return ExecutionOutcome.NoChanges.Success(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = executionStartTimestamp,
        executionEndTimestamp = Clock.System.now(),
        message = "Human intervention required",
    )
}
