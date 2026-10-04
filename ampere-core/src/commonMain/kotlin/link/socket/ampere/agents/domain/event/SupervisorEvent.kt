package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.execution.dispatch.DispatchPhase
import link.socket.ampere.agents.execution.dispatch.DispatchRecord

/**
 * What the supervisor's claim-record journal wrote, carried on the `EventSerialBus`
 * so a dispatch is traceable while it runs and not only after a crash (AMPR-307).
 *
 * The journal is the durable record; these events are the observable one. They are
 * published *after* the write they describe has been renamed into place, so an event
 * on the bus always has a journal line behind it — never the other way round.
 *
 * Lives here rather than beside `DispatchJournal` for the same reason [BenchEvent]
 * and [ProbeEvent] do: [Event] is a sealed interface, and Kotlin requires sealed
 * subtypes to share both module and package with the base type.
 */
@Serializable
sealed interface SupervisorEvent : Event {

    /** The supervisor process whose journal this event came from. */
    val instanceId: String

    /**
     * A claim record reached the journal.
     *
     * Carries the identifying and recovery-relevant fields of the [DispatchRecord]
     * rather than the record itself: the path and branch are long, and every event
     * here is stored in the trace.
     *
     * @property processGroupId The agent subprocess's group, once one exists. Null
     *   before the agent is spawned.
     */
    @Serializable
    data class DispatchRecorded(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val instanceId: String,
        val ticketId: String,
        val phase: DispatchPhase,
        val processGroupId: Long? = null,
        val mergeRequestUrl: String? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : SupervisorEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Dispatch $ticketId: ${phase.name}")
            processGroupId?.let { append(" (pgid $it)") }
            mergeRequestUrl?.let { append(" $it") }
            append(" [$instanceId]")
        }

        companion object {
            const val EVENT_TYPE: EventType = "DispatchRecorded"
        }
    }

    /**
     * The journal was marked as cleanly shut down.
     *
     * Its absence is the whole point of the marker: every dispatch in a journal
     * without one is suspect, so this event is the only positive signal that a
     * supervisor's dispatches need no recovery.
     *
     * @property dispatchCount How many distinct dispatches the journal recorded.
     */
    @Serializable
    data class CleanShutdownMarked(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val instanceId: String,
        val dispatchCount: Int,
        override val urgency: Urgency = Urgency.LOW,
    ) : SupervisorEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Supervisor $instanceId shut down cleanly after $dispatchCount dispatch(es)"

        companion object {
            const val EVENT_TYPE: EventType = "CleanShutdownMarked"
        }
    }

    /**
     * A journal line could not be read as a record, so it was quarantined.
     *
     * Reported rather than dropped for the same reason [StoreRowUndecodableEvent] is:
     * the line is on disk and invisible, and if it was a claim record then a claim or
     * an agent process-group that nothing can now enumerate is still out there. The
     * recovery pass that found it has to be able to say so.
     *
     * @property lineNumber 1-based position in the journal file.
     * @property reason Why the line did not parse — a truncated record, or a record
     *   found after the clean-shutdown marker.
     */
    @Serializable
    data class JournalLineQuarantined(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val instanceId: String,
        val lineNumber: Int,
        val reason: String,
        override val urgency: Urgency = Urgency.HIGH,
    ) : SupervisorEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Quarantined journal line $lineNumber of $instanceId: $reason"

        companion object {
            const val EVENT_TYPE: EventType = "JournalLineQuarantined"
        }
    }
}
