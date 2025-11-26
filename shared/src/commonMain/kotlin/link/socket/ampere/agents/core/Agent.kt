package link.socket.ampere.agents.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.outcomes.OutcomeId
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.IdeaId
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.PerceptionId
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.reasoning.PlanId
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.core.tasks.TaskId
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.tools.Tool
import link.socket.ampere.domain.ai.model.AIModel

typealias AgentId = String

@Serializable
sealed class Agent <S : AgentState> {

    abstract val id: AgentId
    abstract val initialState: S
    abstract val agentConfiguration: AgentConfiguration

    abstract suspend fun perceiveState(vararg newIdeas: Idea): Idea
    abstract suspend fun determinePlanForTask(task: Task, vararg ideas: Idea): Plan
    abstract suspend fun executePlan(plan: Plan): Outcome
    abstract suspend fun runTask(task: Task): Outcome
    abstract suspend fun runTool(tool: Tool<*>, request: ExecutionRequest<*>): ExecutionOutcome
    abstract suspend fun evaluateNextIdeaFromOutcomes(vararg outcomes: Outcome): Idea

    private val _stateFlow: MutableStateFlow<S> by lazy { MutableStateFlow(initialState) }
    val stateFlow: StateFlow<S> by lazy { _stateFlow.asStateFlow() }

    fun getCurrentState(): S = stateFlow.value
    fun getAIModel(): AIModel = agentConfiguration.aiConfiguration.model

    fun getAllIdeas(): List<IdeaId> = with(getCurrentState()) {
        val currentIdea = getCurrentMemory().idea.id
        val pastIdeas = getPastMemory().ideas
        pastIdeas.plus(currentIdea)
    }

    fun getAllPlans(): List<PlanId> = with(getCurrentState()) {
        val currentPlan = getCurrentMemory().plan.id
        val pastPlans = getPastMemory().plans
        pastPlans.plus(currentPlan)
    }

    fun getAllTasks(): List<TaskId> = with(getCurrentState()) {
        val currentTask = getCurrentMemory().task.id
        val pastTasks = getPastMemory().tasks
        pastTasks.plus(currentTask)
    }

    fun getAllOutcomes(): List<OutcomeId> = with(getCurrentState()) {
        val currentOutcome = getCurrentMemory().outcome.id
        val pastOutcomes = getPastMemory().outcomes
        pastOutcomes.plus(currentOutcome)
    }

    fun getAllPerceptions(): List<PerceptionId> = with(getCurrentState()) {
        val currentPerception = getCurrentMemory().perception.id
        val pastPerceptions = getPastMemory().perceptions
        pastPerceptions.plus(currentPerception)
    }

    protected fun rememberNewIdea(idea: Idea) {
        val currentState = getCurrentState()
        currentState.setNewIdea(idea)
        _stateFlow.value = currentState
    }

    protected fun rememberNewOutcome(outcome: Outcome) {
        val currentState = getCurrentState()
        currentState.setNewOutcome(outcome)
        _stateFlow.value = currentState
    }

    protected fun rememberNewPerception(perception: Perception<*>) {
        val currentState = getCurrentState()
        currentState.setNewPerception(perception)
        _stateFlow.value = currentState
    }

    protected fun rememberNewPlan(plan: Plan) {
        val currentState = getCurrentState()
        currentState.setNewPlan(plan)
        _stateFlow.value = currentState
    }

    protected fun rememberNewTask(task: Task) {
        val currentState = getCurrentState()
        currentState.setNewTask(task)
        _stateFlow.value = currentState
    }

    protected fun finishCurrentIdea() {
        val currentState = getCurrentState()
        currentState.setNewIdea(Idea.blank)
        _stateFlow.value = currentState
    }

    protected fun finishCurrentOutcome() {
        val currentState = getCurrentState()
        currentState.setNewOutcome(Outcome.blank)
        _stateFlow.value = currentState
    }

    protected fun finishCurrentPerception() {
        val currentState = getCurrentState()
        currentState.setNewPerception(Perception.blank)
    }

    protected fun finishCurrentPlan() {
        val currentState = getCurrentState()
        currentState.setNewPlan(Plan.blank)
        _stateFlow.value = currentState
    }

    protected fun finishCurrentTask() {
        val currentState = getCurrentState()
        currentState.setNewTask(Task.blank)
        _stateFlow.value = currentState
    }

    protected fun resetCurrentMemory() {
        val currentState = getCurrentState()
        currentState.resetCurrentMemoryCell()
        _stateFlow.value = currentState
    }

    protected fun resetPastMemory() {
        val currentState = getCurrentState()
        currentState.resetPastMemoryCell()
        _stateFlow.value = currentState
    }

    protected fun resetAllMemory() {
        resetCurrentMemory()
        resetPastMemory()
    }
}
