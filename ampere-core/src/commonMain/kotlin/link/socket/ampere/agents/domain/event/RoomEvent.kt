package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.messages.MessageId
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.Verdict
import link.socket.ampere.room.Author
import link.socket.ampere.room.ReviewId
import link.socket.ampere.room.RoomCard
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.roster.RoleId

/**
 * What happened in a Room, on the bus (AMPR-379).
 *
 * The thread primitive already publishes `MessageEvent` for every thread and post a
 * Room makes. These carry the facts the primitive has no field for: which project a
 * Room is for, what a thread is *about* and who it is assigned to, who authored a
 * post and the card on it, and a review's request and verdict. Payloads are
 * primitives and Ampere-owned value types only, so a consumer rendering the Room
 * reads them without importing the roster's service layer.
 *
 * Lives here, not in `room`, because `Event` is sealed and Kotlin requires sealed
 * subtypes to share module and package with the base type.
 */
@Serializable
sealed interface RoomEvent : Event {

    /** The Room this event belongs to (`RoomId.value`). */
    val roomId: String

    /** The Room for a project was opened for the first time. */
    @Serializable
    @SerialName("RoomEvent.RoomOpened")
    data class RoomOpened(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val roomId: String,
        val projectId: String,
        val generalThreadId: MessageThreadId,
        val milestones: Int,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : RoomEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Room $roomId opened for project $projectId with $milestones milestone(s) ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "RoomOpened"
        }
    }

    /** A thread for [subject] was opened in the Room, assigned to [assignedTo] when there is a role to resolve it. */
    @Serializable
    @SerialName("RoomEvent.ThreadOpened")
    data class ThreadOpened(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val roomId: String,
        val threadId: MessageThreadId,
        val subject: ThreadSubject,
        val openedBy: Author,
        val assignedTo: RoleId? = null,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : RoomEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Room thread opened: ${subject.key}")
            assignedTo?.let { append(" (assigned to ${it.value})") }
            append(" ${formatUrgency(urgency)} from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RoomThreadOpened"
        }
    }

    /** A post landed in a Room thread. Follows the `MessageEvent.MessagePosted` it is caused by. */
    @Serializable
    @SerialName("RoomEvent.Posted")
    data class Posted(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val roomId: String,
        val threadId: MessageThreadId,
        val subject: ThreadSubject?,
        val messageId: MessageId,
        val author: Author,
        val body: String,
        val card: RoomCard? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : RoomEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("${author.label} posted in ${subject?.key ?: threadId}: ")
            append(body.take(60))
            if (body.length > 60) append("...")
            card?.let { append(" [${it::class.simpleName}]") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RoomPosted"
        }
    }

    /** A Room thread was closed — a verdict thread when its Probe holds again. */
    @Serializable
    @SerialName("RoomEvent.ThreadResolved")
    data class ThreadResolved(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val roomId: String,
        val threadId: MessageThreadId,
        val subject: ThreadSubject?,
        val resolvedBy: Author,
        val reason: String,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : RoomEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String =
            "Room thread resolved: ${subject?.key ?: threadId} by ${resolvedBy.label} — $reason " +
                formatUrgency(urgency)

        companion object {
            const val EVENT_TYPE: EventType = "RoomThreadResolved"
        }
    }

    /** A card from [author] is held until [reviewer] posts a verdict on it. */
    @Serializable
    @SerialName("RoomEvent.ReviewRequested")
    data class ReviewRequested(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val roomId: String,
        val threadId: MessageThreadId,
        val reviewId: ReviewId,
        val author: RoleId,
        val reviewer: RoleId,
        val card: RoomCard,
        override val urgency: Urgency = Urgency.LOW,
    ) : RoomEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Review ${reviewId.value} requested: ${reviewer.value} to review ${author.value}'s " +
            "${card::class.simpleName} ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "RoomReviewRequested"
        }
    }

    /** The reviewer's verdict on a held card; [released] says whether the card reached the Room. */
    @Serializable
    @SerialName("RoomEvent.ReviewCompleted")
    data class ReviewCompleted(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val roomId: String,
        val threadId: MessageThreadId,
        val reviewId: ReviewId,
        val reviewer: RoleId,
        val probeId: ProbeId,
        val verdict: Verdict,
        val released: Boolean,
        val messageId: MessageId? = null,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : RoomEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Review ${reviewId.value} ${if (released) "released" else "withheld"} by ${reviewer.value} " +
            "(${probeId.value}: ${verdict.reason ?: "holds"}) ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "RoomReviewCompleted"
        }
    }
}
