package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency

/**
 * Undertakings that are not tickets (AMPR-475, F36).
 *
 * A ticket's commitment has no event of its own: it is the fold of `TicketAssigned` and
 * `TicketStatusChanged`. A consumer's undertaking is not a ticket — a work session the person
 * planned, say, with an end time — so nothing on the log would announce it. These events do.
 * The consumer publishes one through its door first, then hands it to `CommitmentLedger`
 * (`undertaken` / `released`) with the `recorded_at` the door returned, and the row's
 * provenance is the event.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its module
 * and package. The committer travels as the `String` value of its Subject ref, which is what the
 * commitment row stores too.
 */
@Serializable
sealed interface CommitmentEvent : Event {

    /** The work the commitment is for: the consumer's own id for it. */
    val workId: String

    /**
     * A Subject undertook a piece of work, due by [due].
     *
     * @property committer Who undertook it: the `Ref` value of a Subject, the Principal's for a
     *   person's own plan.
     * @property workId The consumer's own id for the work. It shares the ledger's key with ticket
     *   ids, so it must not be one.
     * @property due When the work is due: the end of the planned session, for one.
     * @property description What the work is, in the consumer's words. Display only: the
     *   commitment row does not carry it.
     * @property runId The run the undertaking was made in, or null for one made outside a run.
     *   Pass the same value as `publish`'s `runId`.
     */
    @Serializable
    @SerialName("CommitmentEvent.Undertaken")
    data class Undertaken(
        override val eventId: EventId,
        val committer: String,
        override val workId: String,
        val due: Instant,
        val description: String,
        val runId: RunId? = null,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
    ) : CommitmentEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("$committer undertook '$description' ($workId), due $due")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "CommitmentUndertaken"
        }
    }

    /**
     * Whoever undertook [workId] no longer owes it: the work was done, or the plan dropped it.
     *
     * @property reason Why, in the consumer's words. Display only: nothing here judges whether
     *   the commitment was kept.
     * @property runId The run the release was made in, or null for one made outside a run.
     *   Pass the same value as `publish`'s `runId`.
     */
    @Serializable
    @SerialName("CommitmentEvent.Released")
    data class Released(
        override val eventId: EventId,
        override val workId: String,
        val reason: String,
        val runId: RunId? = null,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
    ) : CommitmentEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Released $workId: $reason")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "CommitmentReleased"
        }
    }
}
