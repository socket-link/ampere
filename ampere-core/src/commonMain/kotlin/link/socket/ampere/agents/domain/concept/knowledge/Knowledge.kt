package link.socket.ampere.agents.domain.concept.knowledge

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.outcome.OutcomeId
import link.socket.ampere.agents.domain.concept.IdeaId
import link.socket.ampere.agents.domain.concept.PerceptionId
import link.socket.ampere.agents.domain.concept.PlanId
import link.socket.ampere.agents.domain.concept.task.TaskId

/**
 * A past attempt at solving a similar problem.
 * Used for learning from previous memories.
 */
@Serializable
sealed class Knowledge {

    /** Description of the approach that was tried during this attempt */
    abstract val approach: String

    /** Description of how the approach+outcome can improve efficiency for another agent  */
    abstract val learnings: String

    /** The time when this attempt was made */
    abstract val timestamp: Instant

    @Serializable
    data class FromIdea(
        val ideaId: IdeaId,
        override val approach: String,
        override val learnings: String,
        override val timestamp: Instant,
    ) : Knowledge()

    @Serializable
    data class FromOutcome(
        val outcomeId: OutcomeId,
        override val approach: String,
        override val learnings: String,
        override val timestamp: Instant,
    ) : Knowledge()

    @Serializable
    data class FromPerception(
        val perceptionId: PerceptionId,
        override val approach: String,
        override val learnings: String,
        override val timestamp: Instant,
    ) : Knowledge()

    @Serializable
    data class FromPlan(
        val planId: PlanId,
        override val approach: String,
        override val learnings: String,
        override val timestamp: Instant,
    ) : Knowledge()

    @Serializable
    data class FromTask(
        val taskId: TaskId,
        override val approach: String,
        override val learnings: String,
        override val timestamp: Instant,
    ) : Knowledge()
}
