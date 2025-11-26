package link.socket.ampere.agents.core.states

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.memory.AgentMemory
import link.socket.ampere.agents.core.memory.AgentMemoryCell
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.outcomes.OutcomeId
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.IdeaId
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.PerceptionId
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.reasoning.PlanId
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.core.tasks.TaskId

@Serializable
open class AgentState : State {

    private var memory: AgentMemory = AgentMemory.blank

    fun getCurrentMemory(): AgentMemoryCell.Current = memory.currentMemoryCell
    fun getPastMemory(): AgentMemoryCell.Past = memory.pastMemoryCell
    fun getAdditionalMemory(): List<AgentMemoryCell> = memory.additionalMemoryCells

    fun setNewIdea(
        newIdea: Idea,
    ) {
        addToPastMemory(
            rememberedIdeas = listOf(memory.currentMemoryCell.idea.id),
        )

        memory = memory.copy(
            currentMemoryCell = memory.currentMemoryCell.copy(
                idea = newIdea,
            )
        )
    }

    fun setNewOutcome(
        newOutcome: Outcome,
    ) {
        addToPastMemory(
            rememberedOutcomes = listOf(memory.currentMemoryCell.outcome.id),
        )

        memory = memory.copy(
            currentMemoryCell = memory.currentMemoryCell.copy(
                outcome = newOutcome,
            )
        )
    }

    fun setNewPerception(
        newPerception: Perception<*>,
    ) {
        addToPastMemory(
            rememberedPerceptions = listOf(memory.currentMemoryCell.perception.id),
        )

        memory = memory.copy(
            currentMemoryCell = memory.currentMemoryCell.copy(
                perception = newPerception,
            )
        )
    }

    fun setNewPlan(
        newPlan: Plan,
    ) {
        addToPastMemory(
            rememberedPlans = listOf(memory.currentMemoryCell.plan.id),
        )

        memory = memory.copy(
            currentMemoryCell = memory.currentMemoryCell.copy(
                plan = newPlan,
            )
        )
    }

    fun setNewTask(
        newTask: Task,
    ) {
        addToPastMemory(
            rememberedTasks = listOf(memory.currentMemoryCell.task.id),
        )

        memory = memory.copy(
            currentMemoryCell = memory.currentMemoryCell.copy(
                task = newTask,
            )
        )
    }

    fun addToPastMemory(
        rememberedIdeas: List<IdeaId> = emptyList(),
        rememberedOutcomes: List<OutcomeId> = emptyList(),
        rememberedPerceptions: List<PerceptionId> = emptyList(),
        rememberedPlans: List<PlanId> = emptyList(),
        rememberedTasks: List<TaskId> = emptyList(),
    ) {
        memory = memory.copy(
            pastMemoryCell = with(memory.pastMemoryCell){
                copy(
                    ideas = ideas.plus(rememberedIdeas.filter { it != "" }),
                    outcomes = outcomes.plus(rememberedOutcomes.filter { it != "" }),
                    perceptions = perceptions.plus(rememberedPerceptions.filter { it != "" }),
                    plans = plans.plus(rememberedPlans.filter { it != "" }),
                    tasks = tasks.plus(rememberedTasks.filter { it != "" }),
                )
            }
        )
    }

    fun addToPastKnowledge(
        rememberedKnowledgeFromIdeas: List<Knowledge.FromIdea> = emptyList(),
        rememberedKnowledgeFromOutcomes: List<Knowledge.FromOutcome> = emptyList(),
        rememberedKnowledgeFromPerceptions: List<Knowledge.FromPerception> = emptyList(),
        rememberedKnowledgeFromPlans: List<Knowledge.FromPlan> = emptyList(),
        rememberedKnowledgeFromTasks: List<Knowledge.FromTask> = emptyList(),
    ) {
        memory = memory.copy(
            pastMemoryCell = with(memory.pastMemoryCell){
                copy(
                    knowledgeFromIdeas = knowledgeFromIdeas.plus(rememberedKnowledgeFromIdeas),
                    knowledgeFromOutcomes = knowledgeFromOutcomes.plus(rememberedKnowledgeFromOutcomes),
                    knowledgeFromPerceptions = knowledgeFromPerceptions.plus(rememberedKnowledgeFromPerceptions),
                    knowledgeFromPlans = knowledgeFromPlans.plus(rememberedKnowledgeFromPlans),
                    knowledgeFromTasks = knowledgeFromTasks.plus(rememberedKnowledgeFromTasks),
                )
            }
        )
    }

    fun resetCurrentMemoryCell() {
        val currentMemoryCell = memory.currentMemoryCell

        addToPastMemory(
            rememberedIdeas = listOf(currentMemoryCell.idea.id),
            rememberedOutcomes = listOf(currentMemoryCell.outcome.id),
            rememberedPerceptions = listOf(currentMemoryCell.perception.id),
            rememberedPlans = listOf(currentMemoryCell.plan.id),
            rememberedTasks = listOf(currentMemoryCell.task.id),
        )

        memory = memory.copy(
            currentMemoryCell = AgentMemoryCell.Current.blank,
        )
    }

    fun resetPastMemoryCell() {
        memory = memory.copy(
            pastMemoryCell = AgentMemoryCell.Past.blank
        )
    }
}
