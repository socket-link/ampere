package link.socket.ampere.standup

import kotlin.time.Duration
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.roster.calibration.CalibratedEstimate

/**
 * The deterministic half of a standup (AMPR-379): read the event stream since the
 * last one, decide what is left, and place it in time. Pure functions over values,
 * so every rule is testable in `commonTest` with no bus, store, or clock.
 *
 * Watt discipline: nothing here calls a model. Re-plans between standups are graph
 * work, and this is the graph work.
 */
object StandupDeliberation {

    /** A task the stream says is blocked, and by what. */
    data class Blocked(val taskId: String, val blockedBy: String, val reason: String)

    /** A Probe verdict the stream has not seen resolved. */
    data class OpenVerdict(val subjectId: String, val probeId: ProbeId, val kind: String, val reason: String)

    /** What the stream since the last standup says happened. */
    data class Digest(
        val eventCount: Int,
        val completed: Set<String>,
        val started: Set<String>,
        val blocked: List<Blocked>,
        val openVerdicts: List<OpenVerdict>,
        val escalatedThreads: Set<MessageThreadId>,
        val resolvedThreads: Set<MessageThreadId>,
    )

    /** The Scheduler's placement of the remaining work. */
    data class Schedule(
        val sessions: List<ProposedSession>,
        val projectedFinish: Instant?,
        val remainingWork: Duration,
        val unscheduled: List<CanonId>,
    )

    /**
     * Fold the events since [since] into a [Digest]. Events before [since] are ignored
     * even if handed in; a verdict is open until the same Probe holds on the same
     * subject or the Room resolves its thread.
     */
    fun digest(events: List<Event>, since: Instant): Digest {
        val window = events.filter { it.timestamp >= since }.sortedBy { it.timestamp }
        val completed = mutableSetOf<String>()
        val started = mutableSetOf<String>()
        val blocked = mutableListOf<Blocked>()
        val open = linkedMapOf<Pair<String, ProbeId>, OpenVerdict>()
        val escalated = mutableSetOf<MessageThreadId>()
        val resolved = mutableSetOf<MessageThreadId>()

        window.forEach { event ->
            when (event) {
                is TaskEvent.TaskStarted -> started += event.taskId
                is TaskEvent.TaskCompleted -> {
                    completed += event.taskId
                    blocked.removeAll { it.taskId == event.taskId }
                }
                is TaskEvent.TaskBlocked -> blocked += Blocked(event.taskId, event.blockedByTaskId, event.reason)
                is ProbeEvent.VerdictReached -> {
                    val key = event.subjectId to event.probeId
                    when (val verdict = event.verdict) {
                        is ProbeVerdict.Holds -> open.remove(key)
                        is ProbeVerdict.Warn -> Unit
                        is ProbeVerdict.Violated -> open[key] = OpenVerdict(
                            event.subjectId,
                            event.probeId,
                            "violated",
                            verdict.reason,
                        )
                        is ProbeVerdict.Undetermined -> open[key] = OpenVerdict(
                            event.subjectId,
                            event.probeId,
                            "undetermined",
                            "${verdict.reason} (${verdict.cause.name})",
                        )
                    }
                }
                is MessageEvent.EscalationRequested -> escalated += event.threadId
                is RoomEvent.ThreadResolved -> {
                    resolved += event.threadId
                    (event.subject as? ThreadSubject.Verdict)?.let { subject ->
                        open.keys.filter { it.first == subject.subjectId }.forEach { open.remove(it) }
                    }
                }
                else -> Unit
            }
        }

        return Digest(
            eventCount = window.size,
            completed = completed,
            started = started,
            blocked = blocked,
            openVerdicts = open.values.toList(),
            escalatedThreads = escalated,
            resolvedThreads = resolved,
        )
    }

    /** The items still to do: not done or cancelled in the graph, and not completed since the last standup. */
    fun remaining(graph: CanonWorkGraph, digest: Digest): List<CanonWorkItem> =
        graph.items.filter { item ->
            item.status != CanonWorkStatus.DONE &&
                item.status != CanonWorkStatus.CANCELLED &&
                item.canonId.value !in digest.completed
        }

    /**
     * A dependency order over [items]: every item after the items it depends on.
     * Each step places the first item in graph order whose dependencies are placed,
     * so the result is deterministic. Dependencies outside [items] are treated as
     * satisfied. Items left on a cycle are appended in graph order — the sequence
     * Probe convicts the cycle; this only has to place things.
     */
    fun order(items: List<CanonWorkItem>): List<CanonWorkItem> {
        val ids = items.map { it.canonId }.toSet()
        val pending = items.toMutableList()
        val placed = linkedSetOf<CanonId>()
        val ordered = mutableListOf<CanonWorkItem>()

        while (pending.isNotEmpty()) {
            val next = pending.firstOrNull { item -> item.dependsOn.all { it !in ids || it in placed } }
                ?: break
            pending -= next
            placed += next.canonId
            ordered += next
        }
        ordered += pending
        return ordered
    }

    /**
     * Place [estimates] into [availability] in [order], from [from]. With no windows,
     * one unbounded window opens at [from]. An item may span several windows; an
     * item with no estimate is skipped and named in the proposal's rationale by the
     * caller. Work that does not fit the windows is reported, not squeezed.
     */
    fun schedule(
        estimates: List<CalibratedEstimate>,
        order: List<CanonId>,
        availability: List<AvailabilityWindow>,
        from: Instant,
    ): Schedule {
        val byItem = estimates.associateBy { it.itemId }
        val windows = availability
            .filter { it.end > from }
            .map { AvailabilityWindow(start = maxOf(it.start, from), end = it.end) }
            .sortedBy { it.start }
            .ifEmpty { listOf(AvailabilityWindow(start = from, end = Instant.DISTANT_FUTURE)) }

        val sessions = mutableListOf<ProposedSession>()
        val unscheduled = mutableListOf<CanonId>()
        var remainingWork = Duration.ZERO
        var windowIndex = 0
        var cursor = windows.first().start

        order.forEach { itemId ->
            val estimate = byItem[itemId] ?: return@forEach
            var left = estimate.duration
            remainingWork += left
            if (left <= Duration.ZERO) return@forEach

            while (left > Duration.ZERO && windowIndex < windows.size) {
                val window = windows[windowIndex]
                if (cursor < window.start) cursor = window.start
                val available = window.end - cursor
                if (available <= Duration.ZERO) {
                    windowIndex += 1
                    continue
                }
                val take = minOf(left, available)
                sessions += ProposedSession(itemId = itemId, start = cursor, end = cursor + take)
                cursor += take
                left -= take
                if (cursor >= window.end) windowIndex += 1
            }
            if (left > Duration.ZERO) unscheduled += itemId
        }

        return Schedule(
            sessions = sessions,
            projectedFinish = if (unscheduled.isEmpty()) sessions.lastOrNull()?.end else null,
            remainingWork = remainingWork,
            unscheduled = unscheduled,
        )
    }
}
