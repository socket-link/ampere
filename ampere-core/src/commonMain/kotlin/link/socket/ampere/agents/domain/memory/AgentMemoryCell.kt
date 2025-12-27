package link.socket.ampere.agents.domain.memory

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.IdeaId
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.PerceptionId
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.PlanId
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.outcome.OutcomeId
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.concept.task.TaskId

@Serializable
sealed interface AgentMemoryCell {

    @Serializable
    data class Current(
        val idea: Idea,
        val outcome: Outcome,
        val perception: Perception<*>,
        val plan: Plan,
        val task: Task,
    ) : AgentMemoryCell {

        companion object {
            val blank: Current = Current(
                idea = Idea.blank,
                outcome = Outcome.blank,
                perception = Perception.blank,
                plan = Plan.blank,
                task = Task.blank,
            )
        }
    }

    @Serializable
    data class Past(
        val ideas: List<IdeaId>,
        val outcomes: List<OutcomeId>,
        val perceptions: List<PerceptionId>,
        val plans: List<PlanId>,
        val tasks: List<TaskId>,
        val knowledgeFromIdeas: List<Knowledge.FromIdea>,
        val knowledgeFromOutcomes: List<Knowledge.FromOutcome>,
        val knowledgeFromPerceptions: List<Knowledge.FromPerception>,
        val knowledgeFromPlans: List<Knowledge.FromPlan>,
        val knowledgeFromTasks: List<Knowledge.FromTask>,
    ) : AgentMemoryCell {

        companion object {
            val blank: Past = Past(
                ideas = emptyList(),
                outcomes = emptyList(),
                perceptions = emptyList(),
                plans = emptyList(),
                tasks = emptyList(),
                knowledgeFromIdeas = emptyList(),
                knowledgeFromOutcomes = emptyList(),
                knowledgeFromPerceptions = emptyList(),
                knowledgeFromPlans = emptyList(),
                knowledgeFromTasks = emptyList(),
            )
        }
    }
}
