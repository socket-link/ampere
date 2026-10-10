package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.execution.dispatch.BranchVerdict
import link.socket.ampere.agents.execution.dispatch.ClaimVerdict
import link.socket.ampere.agents.execution.dispatch.DispatchPhase
import link.socket.ampere.agents.execution.dispatch.DispatchRecord
import link.socket.ampere.agents.execution.dispatch.ReconciliationSource
import link.socket.ampere.agents.execution.dispatch.WorktreeVerdict
import link.socket.ampere.agents.execution.process.TerminationOutcome

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

    /**
     * A dead supervisor's journal was marked as settled by a reconciliation pass
     * (AMPR-310, AMPR-291 mechanism M-C).
     *
     * The counterpart to [CleanShutdownMarked]: the supervisor never came back, but
     * its process groups have been reaped, its worktrees repaired and its claims
     * released, so its dispatches are no longer suspect. [instanceId] is the *dead*
     * instance, not the one that ran the pass — the marker is a fact about that
     * journal, and [reconciledBy] is who established it.
     */
    @Serializable
    data class JournalReconciled(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val instanceId: String,
        val reconciledBy: String,
        val passId: String,
        val dispatchCount: Int,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : SupervisorEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String =
            "Reconciled supervisor $instanceId: $dispatchCount dispatch(es) settled by $reconciledBy"

        companion object {
            const val EVENT_TYPE: EventType = "JournalReconciled"
        }
    }

    /**
     * What a reconciliation pass did about one suspect dispatch.
     *
     * One per dispatch, carrying the four residue classes' verdicts rather than the
     * whole [DispatchDisposition][link.socket.ampere.agents.execution.dispatch.DispatchDisposition]:
     * the notes are prose for a person and the paths are long, and every event here
     * is stored in the trace. The full disposition is in the journal's
     * reconciliation marker.
     *
     * @property instanceId The *dead* supervisor whose dispatch this was.
     * @property reaped What stopping the agent's process group did, or null when
     *   the record named no address.
     * @property settled Whether the pass needed nothing further for this dispatch.
     *   False means a person has to decide — held work, or a ticket a human moved.
     */
    @Serializable
    data class DispatchReconciled(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val instanceId: String,
        val passId: String,
        val ticketId: String,
        val reaped: TerminationOutcome? = null,
        val worktree: WorktreeVerdict,
        val branch: BranchVerdict,
        val claim: ClaimVerdict,
        val settled: Boolean,
        override val urgency: Urgency = if (settled) Urgency.LOW else Urgency.HIGH,
    ) : SupervisorEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append(if (settled) "Reconciled" else "Held")
            append(" $ticketId: worktree ${worktree.name}, branch ${branch.name}, claim ${claim.name}")
            reaped?.let { append(", group ${it.name}") }
            append(" [$instanceId]")
        }

        companion object {
            const val EVENT_TYPE: EventType = "DispatchReconciled"
        }
    }

    /**
     * A reconciliation pass finished.
     *
     * The one event that says whether recovery *worked*: [held] is residue the pass
     * deliberately refused to touch, and [quarantinedLines] is residue nothing can
     * even enumerate — the original AMPR-291 failure, so it carries the pass's
     * urgency up with it.
     *
     * @property instanceId The supervisor instance that ran the pass.
     * @property source Where the suspect dispatches came from; a degraded pass read
     *   no journal and could only see claims.
     */
    @Serializable
    data class ReconciliationCompleted(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val instanceId: String,
        val passId: String,
        val source: ReconciliationSource,
        val journalsRead: Int,
        val journalsSettled: Int,
        val settled: Int,
        val held: Int,
        val quarantinedLines: Int,
        override val urgency: Urgency = when {
            quarantinedLines > 0 -> Urgency.HIGH
            held > 0 -> Urgency.MEDIUM
            else -> Urgency.LOW
        },
    ) : SupervisorEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Reconciliation ${source.name}: $settled settled")
            if (held > 0) append(", $held held for a human")
            if (quarantinedLines > 0) append(", $quarantinedLines unreadable journal line(s)")
            append(" ($journalsSettled/$journalsRead journal(s) marked) [$instanceId]")
        }

        companion object {
            const val EVENT_TYPE: EventType = "ReconciliationCompleted"
        }
    }
}
