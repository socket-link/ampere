package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency

/**
 * The Goal branch's announcements (AMPR-419, F8).
 *
 * A `GoalStore` row is *state*; these events are the record it is folded from. The goal branch
 * publishes the event first, through the one door and under the run the goal belongs to, and
 * writes the row with the event's id as its provenance — so every persisted goal points back at
 * the event that formed it, and a fold over the log recreates the branch without reading it.
 *
 * Before this family nothing about a goal reached the log: Charge built its `GoalTree` in memory
 * and published nothing, so there was no existing kind to follow. A tracker's view of a goal is
 * not announced here — it is a `Mirrors` edge, and edges go out as
 * [SubjectEvent.RelationshipRecorded] like every other edge.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its module
 * and package. Refs travel as the `String` values they wrap, which is what the store holds too.
 */
@Serializable
sealed interface GoalEvent : Event {

    /** The `Ref` value of the goal this event is about. */
    val goalId: String

    /**
     * A goal was formed: the agent now holds it, and the branch has a row for it.
     *
     * @property parentId the enclosing goal's `Ref` value, or null at the root of a tree.
     * @property ordinal the goal's place among its siblings — the order Flow works them in.
     * @property description what the goal is, in the words it was formed with.
     * @property subjectId whose goal it is: the `Ref` value of the Subject that formed it.
     * @property runId the Arc run that formed it, or null for a goal no run formed.
     */
    @Serializable
    @SerialName("GoalEvent.GoalFormed")
    data class GoalFormed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val goalId: String,
        val parentId: String?,
        val ordinal: Int,
        val description: String,
        val subjectId: String,
        val runId: String? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : GoalEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Goal formed: $description ($goalId)")
            parentId?.let { append(" under $it") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GoalFormed"
        }
    }

    /**
     * A goal's status moved — Flow judged it complete, for one.
     *
     * @property status the name of the `GoalStatus` the goal is now in.
     * @property runId the Arc run the goal belongs to, or null for a goal no run formed.
     */
    @Serializable
    @SerialName("GoalEvent.GoalUpdated")
    data class GoalUpdated(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val goalId: String,
        val status: String,
        val runId: String? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : GoalEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Goal $goalId is now ${status.lowercase()}")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GoalUpdated"
        }
    }
}
