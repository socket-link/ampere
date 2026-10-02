package link.socket.ampere.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.roster.Roster

/**
 * Binds a Room to the Probe stream (AMPR-379): a thread per `Violated` and
 * `Undetermined` verdict, assigned to the role that can resolve it, closed when the
 * Probe holds, and the Coordinator's DM when a verdict is the human's to settle.
 *
 * Subscribes to `ProbeEvent.VerdictReached` (the verdicts) and `RoomEvent.Posted`
 * (to count the assigned role's passes). Both feed one inbox drained by one worker,
 * so verdicts are applied in arrival order and a pass is counted before the verdict
 * that follows it is judged — the bus dispatches handlers concurrently, and the
 * policy's "one pass, then the human" rule is an ordering rule.
 *
 * The policy is [VerdictPolicy]; this class only applies its actions to the Room.
 * [awaitVerdictsSeen] and [awaitIdle] let a replay wait for the Room to settle
 * before it publishes the next recorded event.
 *
 * @param subjects which subject ids belong to this Room; a Probe suite judging
 *   another project's plan on the same bus is not this Room's business.
 */
class VerdictThreadBinding(
    private val room: RoomService,
    private val roomId: RoomId,
    private val roster: Roster,
    private val escalation: CoordinatorEscalation,
    private val bus: EventSerialBus,
    private val scope: CoroutineScope,
    private val subjects: (String) -> Boolean = { true },
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    private val policy = VerdictPolicy(roster)
    private val open = MutableStateFlow<Map<String, VerdictPolicy.OpenVerdictThread>>(emptyMap())
    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val queued = MutableStateFlow(0)
    private val _verdictsSeen = MutableStateFlow(0L)
    private var subscriptions: List<Subscription> = emptyList()
    private var worker: Job? = null

    /** How many verdict events the binding has received, applied or not. */
    val verdictsSeen: StateFlow<Long> = _verdictsSeen.asStateFlow()

    /** The verdict threads currently open, as the policy sees them. */
    fun openThreads(): List<VerdictPolicy.OpenVerdictThread> = open.value.values.toList()

    /** How many times the assigned role has posted in [subject]'s thread; zero when it is not open. */
    fun resolverPasses(subject: ThreadSubject.Verdict): Int = open.value[subject.key]?.resolverPasses ?: 0

    suspend fun start() {
        if (worker != null) return
        val agentId = "room-verdicts-${roomId.value}"
        subscriptions = listOf(
            bus.subscribeSuspending(
                agentId,
                ProbeEvent.VerdictReached.EVENT_TYPE,
                EventHandler { event, _ -> enqueue(event) },
            ),
            bus.subscribeSuspending(agentId, RoomEvent.Posted.EVENT_TYPE, EventHandler { event, _ -> enqueue(event) }),
        )
        worker = scope.launch {
            for (event in inbox) {
                runCatching { handle(event) }
                    .onFailure {
                        logger.logError(
                            "Room ${roomId.value} could not apply ${event.eventType}: ${it.message}",
                            it,
                        )
                    }
                queued.update { it - 1 }
            }
        }
    }

    suspend fun stop() {
        subscriptions.forEach { bus.unsubscribeSuspending(it) }
        subscriptions = emptyList()
        worker?.cancel()
        worker = null
    }

    /** Suspends until the binding has received at least [count] verdict events. */
    suspend fun awaitVerdictsSeen(count: Long) {
        verdictsSeen.first { it >= count }
    }

    /** Suspends until every received event has been applied. */
    suspend fun awaitIdle() {
        queued.first { it == 0 }
    }

    private suspend fun enqueue(event: Event) {
        // Queued before seen, so a waiter that has seen the count also sees the queue.
        queued.update { it + 1 }
        if (event is ProbeEvent.VerdictReached) _verdictsSeen.update { it + 1 }
        inbox.send(event)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is ProbeEvent.VerdictReached -> if (subjects(event.subjectId)) {
                apply(
                    policy.decide(event, open.value.values),
                    event,
                )
            }
            is RoomEvent.Posted -> countPass(event)
            else -> Unit
        }
    }

    private fun countPass(posted: RoomEvent.Posted) {
        if (posted.roomId != roomId.value) return
        val author = posted.author as? Author.Role ?: return
        val subject = posted.subject as? ThreadSubject.Verdict ?: return
        open.update { threads ->
            val thread = threads[subject.key] ?: return@update threads
            if (author.id != thread.assignedTo) return@update threads
            threads + (subject.key to thread.copy(resolverPasses = thread.resolverPasses + 1))
        }
    }

    private suspend fun apply(actions: List<VerdictPolicy.Action>, cause: ProbeEvent.VerdictReached) {
        val verifier = Author.Role(roster.verifier)
        val host = Author.Role(roster.host)

        actions.forEach { action ->
            val threadId = roomThreadId(roomId, action.subject)
            when (action) {
                is VerdictPolicy.Action.OpenThread -> {
                    room.thread(roomId, action.subject, action.assignedTo, action.title, causedBy = cause.eventId)
                        .getOrThrow()
                    open.update { threads ->
                        threads + (
                            action.subject.key to VerdictPolicy.OpenVerdictThread(
                                subject = action.subject,
                                probeId = action.probeId,
                                assignedTo = action.assignedTo,
                            )
                            )
                    }
                }

                is VerdictPolicy.Action.PostCard -> {
                    room.post(threadId, verifier, action.body, action.card, causedBy = cause.eventId)
                        .onFailure { logger.logError("Verdict card not posted to $threadId: ${it.message}") }
                }

                is VerdictPolicy.Action.Escalate -> {
                    room.post(threadId, host, "Asking the human: ${action.reason}", causedBy = cause.eventId)
                        .onFailure { logger.logError("Escalation notice not posted to $threadId: ${it.message}") }
                    escalation.escalate(threadId, action.reason, action.context, causedBy = cause.eventId)
                    open.update { threads ->
                        val thread = threads[action.subject.key] ?: return@update threads
                        threads + (action.subject.key to thread.copy(escalated = true))
                    }
                }

                is VerdictPolicy.Action.Resolve -> {
                    room.resolve(threadId, verifier, action.reason, causedBy = cause.eventId).getOrThrow()
                    open.update { it - action.subject.key }
                }
            }
        }
    }
}
