package link.socket.ampere.agents.definition.code

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.perception
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.state.AgentState

@Serializable
data class CodeWriterState(
    val outcome: Outcome,
    val task: Task,
    val plan: Plan,
) : AgentState() {

    fun toPerception(): Perception<CodeWriterState> = perception(this) {
        header("Code Writer Perception State")
        timestamp()

        setNewOutcome(outcome)
        setNewTask(task)
        setNewPlan(plan)
    }

    companion object {
        val blank: CodeWriterState =
            CodeWriterState(
                outcome = Outcome.blank,
                task = Task.blank,
                plan = Plan.blank,
            )
    }
}
