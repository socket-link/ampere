package link.socket.ampere.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/**
 * The Coordinator's DM to the human (AMPR-379): CHI over the path the framework
 * already has, not a fourth one.
 *
 * `AgentMessageApi.escalateToHuman` is the AskHuman path with a thread attached: it
 * moves the thread to `WaitingForHuman` first (the durable record), publishes
 * `MessageEvent.EscalationRequested` and `ThreadStatusChanged`, then produces a
 * `HumanInteractionEvent.InputRequested` — an `EmissionEvent.Produced` of kind
 * **Decision**, the CHI renderer Socket already has — and, when the reply lands,
 * posts it into the thread as the human and reopens it.
 *
 * That last part suspends until the reply or the 30-minute timeout, which is why
 * [escalate] launches it on [scope] and returns: the caller is a bus handler
 * reacting to a verdict, and a handler must not wait on a person. The thread's
 * `WaitingForHuman` is what survives a restart; the in-process reply pairing does
 * not, and a thread still waiting after one is `reopenThread`'s to answer.
 *
 * [messageApi] is built for the host role (`agentId = roster.host.value`), so the
 * escalation and the emission are attributed to the Coordinator.
 */
class CoordinatorEscalation(
    private val messageApi: AgentMessageApi,
    private val scope: CoroutineScope,
    private val runId: RunId? = null,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /**
     * Ask the human about [threadId]. Returns once the ask is launched; the thread is
     * waiting for the human as soon as the launched work's first step commits.
     */
    fun escalate(
        threadId: MessageThreadId,
        reason: String,
        context: Map<String, String> = emptyMap(),
        causedBy: EventId? = null,
    ): Job = scope.launch {
        runCatching {
            messageApi.escalateToHuman(
                threadId = threadId,
                reason = reason,
                context = context,
                awaitReply = true,
                causedBy = causedBy,
                runId = runId,
            ).getOrThrow()
        }.onFailure { throwable ->
            logger.logError(
                message = "Escalation for Room thread $threadId did not complete: ${throwable.message}",
                throwable = throwable,
            )
        }
    }
}
