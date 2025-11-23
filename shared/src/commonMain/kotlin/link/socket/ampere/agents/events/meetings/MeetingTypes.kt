package link.socket.ampere.agents.events.meetings

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.PRId
import link.socket.ampere.agents.core.SprintId
import link.socket.ampere.agents.core.TeamId
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.messages.MessageChannelId
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.tasks.AgendaItem

typealias MeetingId = String
typealias MeetingOutcomeId = String

@Serializable
data class MeetingInvitation(
    val title: String,
    val agenda: List<AgendaItem>,
    val requiredParticipants: List<AssignedTo>,
    val optionalParticipants: List<AssignedTo>? = null,
    val expectedOutcomes: List<MeetingOutcomeRequirements>? = null,
)

@Serializable
data class MeetingMessagingDetails(
    val messageChannelId: MessageChannelId,
    val messageThreadId: MessageThreadId,
)

@Serializable
sealed interface Property<T : Any>

@Serializable
sealed interface MeetingProperty : Property<Meeting>

/** Types of meetings supported by the system. */
@Serializable
sealed interface MeetingType : MeetingProperty {

    @Serializable
    data class Standup(
        val teamId: TeamId,
        val sprintId: SprintId,
    ) : MeetingType

    @Serializable
    data class SprintPlanning(
        val teamId: TeamId,
        val sprintId: SprintId,
    ) : MeetingType

    @Serializable
    data class CodeReview(
        val prId: PRId,
        val prUrl: String,
        val author: EventSource,
        val requestedReviewer: AssignedTo.Agent,
    ) : MeetingType

    @Serializable
    data class AdHoc(
        val reason: String,
    ) : MeetingType
}

/** Types of outcomes that a meeting can produce. */
@Serializable
sealed class MeetingOutcome(
    val id: MeetingOutcomeId,
) {
    enum class Type {
        BLOCKER_RAISED,
        GOAL_CREATED,
        DECISION_MADE,
        ACTION_ITEM,
    }

    @Serializable
    data class BlockerRaised(
        val overrideId: MeetingOutcomeId,
        val description: String,
        val raisedBy: EventSource,
        val assignedTo: AssignedTo? = null,
    ) : MeetingOutcome(overrideId) {

        companion object {
            val TYPE = Type.BLOCKER_RAISED
        }
    }

    @Serializable
    data class GoalCreated(
        val overrideId: MeetingOutcomeId,
        val description: String,
        val createdBy: EventSource,
        val shouldCompleteByEndOfSprint: SprintId? = null,
        val assignedTo: AssignedTo? = null,
        val dueBy: Instant? = null,
    ) : MeetingOutcome(overrideId) {

        companion object {
            val TYPE = Type.GOAL_CREATED
        }
    }

    @Serializable
    data class DecisionMade(
        val overrideId: MeetingOutcomeId,
        val description: String,
        val decidedBy: EventSource,
    ) : MeetingOutcome(overrideId) {

        companion object {
            val TYPE = Type.DECISION_MADE
        }
    }

    @Serializable
    data class ActionItem(
        val overrideId: MeetingOutcomeId,
        val assignedTo: AssignedTo,
        val description: String,
        val shouldBeCompletedByEndOfSprint: SprintId? = null,
        val dueBy: Instant? = null,
    ) : MeetingOutcome(overrideId) {

        companion object {
            val TYPE = Type.ACTION_ITEM
        }
    }
}

/** A concrete outcome produced by a meeting (decision, action item, blocker). */
@Serializable
data class MeetingOutcomeRequirements(
    val requirementsDescription: String,
    val expectedOutcomes: List<MeetingOutcome.Type>? = null,
)

/** Lifecycle status for a meeting. */
@Serializable
sealed class MeetingStatus(
    val scheduledFor: Instant?,
) : MeetingProperty {

    @Serializable
    data class Scheduled(
        val scheduledForOverride: Instant,
    ) : MeetingStatus(scheduledForOverride)

    @Serializable
    data class Delayed(
        val reason: String,
        val scheduledForOverride: Instant? = null,
    ) : MeetingStatus(scheduledForOverride)

    @Serializable
    data class InProgress(
        val startedAt: Instant,
        val messagingDetails: MeetingMessagingDetails,
        val scheduledForOverride: Instant? = null,
    ) : MeetingStatus(scheduledForOverride)

    @Serializable
    data class Completed(
        val completedAt: Instant,
        val attendedBy: List<EventSource>,
        val messagingDetails: MeetingMessagingDetails,
        val scheduledForOverride: Instant? = null,
        val outcomes: List<MeetingOutcome>? = null,
    ) : MeetingStatus(scheduledForOverride)

    @Serializable
    data class Canceled(
        val reason: String,
        val canceledAt: Instant,
        val messagingDetails: MeetingMessagingDetails,
        val scheduledForOverride: Instant? = null,
        val outcomes: List<MeetingOutcome>? = null,
    ) : MeetingStatus(scheduledForOverride)
}
