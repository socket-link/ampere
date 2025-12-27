package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.human.GlobalHumanResponseRegistry
import link.socket.ampere.agents.events.utils.generateUUID
import kotlin.time.Duration.Companion.minutes

actual suspend fun executeAskHuman(
    context: ExecutionContext.NoChanges,
): ExecutionOutcome.NoChanges {
    val executionStartTimestamp = Clock.System.now()
    val requestId = generateUUID()

    // Display the question to console (will also be visible in CLI dashboard through events)
    println("""
        ════════════════════════════════════════
        🤔 HUMAN INPUT REQUIRED
        ════════════════════════════════════════
        Request ID: $requestId
        Executor: ${context.executorId}
        Ticket: ${context.ticket.id}
        Task: ${context.task.id}

        Question: ${context.instructions}

        To respond, use:
        ./ampere-cli/ampere respond $requestId "<your response>"
        ════════════════════════════════════════
    """.trimIndent())

    // Wait for human response (blocks agent execution)
    val humanResponse = GlobalHumanResponseRegistry.instance.waitForResponse(
        requestId = requestId,
        timeout = 30.minutes
    )

    return if (humanResponse != null) {
        ExecutionOutcome.NoChanges.Success(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            message = "Human response: $humanResponse",
        )
    } else {
        ExecutionOutcome.NoChanges.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            message = "Human input request timed out after 30 minutes",
        )
    }
}
