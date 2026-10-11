package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency

/**
 * The Subject registry's announcements (AMPR-416, F30).
 *
 * A Subject row and a Relationship row are *state*; these events are the record that state is
 * folded from. The registry publishes the event first, through the one door, and writes the row
 * with the event's id as its `Provenance.createdBy` — so every registered Subject points back at
 * the event that registered it, and a fold over the log recreates the registry without reading
 * it.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its
 * module and package. The Field types themselves are not named here — the refs travel as the
 * `String` values they wrap, which is what `field` stores too.
 */
@Serializable
sealed interface SubjectEvent : Event {

    /**
     * A Subject was registered: an agent, a human, or a group now has a row the fold can resolve.
     *
     * @property subjectId the `Ref` value — for an agent, its existing `AgentId`; for the
     *   Principal, `EventSource.Human.ID`.
     * @property kind `AGENT`, `HUMAN` or `GROUP`; the name of the `SubjectKind` written to the row.
     * @property displayName the Subject's own label. Display only, never identity.
     */
    @Serializable
    @SerialName("SubjectEvent.SubjectRegistered")
    data class SubjectRegistered(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val subjectId: String,
        val kind: String,
        val displayName: String,
        override val urgency: Urgency = Urgency.LOW,
    ) : SubjectEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Registered ${kind.lowercase()} '$displayName' ($subjectId)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "SubjectRegistered"
        }
    }

    /**
     * An edge was recorded between two primitives — an agent joining a team is a `MemberOf` edge
     * from the member to the group, and nothing else.
     *
     * @property fromId the edge's source `Ref` value — the member, the mirror.
     * @property toId the edge's target `Ref` value — the group, the thing mirrored.
     * @property kind the `RelationshipKind`'s stored name: `MemberOf`, `Mirrors`.
     */
    @Serializable
    @SerialName("SubjectEvent.RelationshipRecorded")
    data class RelationshipRecorded(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val fromId: String,
        val toId: String,
        val kind: String,
        override val urgency: Urgency = Urgency.LOW,
    ) : SubjectEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Recorded $fromId $kind $toId")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RelationshipRecorded"
        }
    }
}
