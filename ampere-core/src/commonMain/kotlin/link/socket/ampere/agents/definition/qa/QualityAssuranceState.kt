package link.socket.ampere.agents.definition.qa

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.perception
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.state.AgentState

@Serializable
data class QualityAssuranceState(
    /** Current task outcome */
    val outcome: Outcome,
    /** Current task being validated */
    val task: Task,
    /** Current validation plan */
    val plan: Plan,
) : AgentState() {

    fun toPerception(): Perception<QualityAssuranceState> =
        perception(this) {
            header("Quality Assurance Agent - State Perception")
            timestamp()

            setNewOutcome(outcome)
            setNewTask(task)
            setNewPlan(plan)
        }

    companion object {
        /**
         * Returns an empty QA state.
         */
        val blank = QualityAssuranceState(
            outcome = Outcome.blank,
            task = Task.Blank,
            plan = Plan.blank,
        )
    }
}
