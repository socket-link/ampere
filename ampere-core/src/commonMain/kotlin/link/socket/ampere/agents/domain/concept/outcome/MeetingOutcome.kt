package link.socket.ampere.agents.domain.concept.outcome

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.SprintId
import link.socket.ampere.agents.domain.event.EventSource

/** Types of outcomes that a meeting can produce. */
@Serializable
sealed interface MeetingOutcome : Outcome {

    @Serializable
    data class BlockerRaised(
        override val id: OutcomeId,
        val description: String,
        val raisedBy: EventSource,
        val assignedTo: AssignedTo? = null,
    ) : MeetingOutcome

    @Serializable
    data class GoalCreated(
        override val id: OutcomeId,
        val description: String,
        val createdBy: EventSource,
        val shouldCompleteByEndOfSprint: SprintId? = null,
        val assignedTo: AssignedTo? = null,
        val dueBy: Instant? = null,
    ) : MeetingOutcome

    @Serializable
    data class DecisionMade(
        override val id: OutcomeId,
        val description: String,
        val decidedBy: EventSource,
    ) : MeetingOutcome

    @Serializable
    data class ActionItem(
        override val id: OutcomeId,
        val assignedTo: AssignedTo,
        val description: String,
        val shouldBeCompletedByEndOfSprint: SprintId? = null,
        val dueBy: Instant? = null,
    ) : MeetingOutcome
}
