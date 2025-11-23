package link.socket.ampere.agents.core

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.tasks.Task
import link.socket.ampere.agents.tools.Tool
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration

typealias AgentId = String

@Serializable
sealed interface State

@Serializable
data class AgentConfiguration(
    val agentDefinition: AgentDefinition,
    val aiConfiguration: AIConfiguration,
)

@Serializable
data class AgentState(
    val currentIdea: Idea = Idea.blank,
    val currentPlan: Plan = Plan.blank,
    val ideaHistory: List<Idea> = emptyList(),
    val planHistory: List<Plan> = emptyList(),
    val taskHistory: List<Task> = emptyList(),
    val outcomeHistory: List<Outcome> = emptyList(),
    val perceptionHistory: List<Perception> = emptyList(),
) : State

@Serializable
abstract class MinimalAgent(
    private val agentConfiguration: AgentConfiguration,
) {
    private var currentState: AgentState = AgentState()

    abstract val id: AgentId

    abstract suspend fun perceiveState(vararg newIdeas: Idea): Idea
    abstract suspend fun planIdea(vararg ideas: Idea): Plan
    abstract suspend fun executePlan(plan: Plan): Outcome
    abstract suspend fun runTask(task: Task): Outcome
    abstract suspend fun runTool(tool: Tool, parameters: Map<String, Any?>): Outcome
    abstract suspend fun evaluateNewIdeas(vararg outcomes: Outcome): Idea

    fun getCurrentState(): AgentState = currentState

    fun getRecentIdeas(): List<Idea> = with(currentState) {
        ideaHistory.plus(currentIdea)
    }

    fun getRecentPlans(): List<Plan> = with(currentState) {
        planHistory.plus(currentPlan)
    }

    fun getRecentTasks(): List<Task> =
        currentState.taskHistory

    fun getRecentOutcomes(): List<Outcome> =
        currentState.outcomeHistory

    fun getRecentPerceptions(): List<Perception> =
        currentState.perceptionHistory

    protected fun finishCurrentIdea() {
        with(currentState) {
            currentState = copy(
                ideaHistory = ideaHistory.plus(currentIdea),
                currentIdea = Idea.blank,
            )
        }
    }

    protected fun finishCurrentPlan() {
        with(currentState) {
            currentState = copy(
                planHistory = planHistory.plus(currentPlan),
                currentPlan = Plan.blank,
            )
        }
    }

    protected fun rememberIdea(idea: Idea) {
        finishCurrentIdea()
        currentState = currentState.copy(
            currentIdea = idea,
        )
    }

    protected fun rememberPlan(plan: Plan) {
        finishCurrentPlan()
        currentState = currentState.copy(
            currentPlan = plan,
        )
    }

    protected fun rememberTask(task: Task) {
        with(currentState) {
            currentState = copy(
                taskHistory = taskHistory.plus(task),
            )
        }
    }

    protected fun rememberOutcome(outcome: Outcome) {
        currentState = currentState.copy(
            outcomeHistory = currentState.outcomeHistory.plus(outcome),
        )
    }

    protected fun rememberPerception(perception: Perception) {
        currentState = currentState.copy(
            perceptionHistory = currentState.perceptionHistory.plus(perception),
        )
    }

    protected fun resetWorkingMemory() {
        finishCurrentIdea()
        finishCurrentPlan()
    }

    protected fun resetPastMemory() {
        currentState = currentState.copy(
            ideaHistory = emptyList(),
            planHistory = emptyList(),
            taskHistory = emptyList(),
            perceptionHistory = emptyList(),
            outcomeHistory = emptyList(),
        )
    }

    protected fun resetAllMemory() {
        resetWorkingMemory()
        resetPastMemory()
    }
}

/**
 * Contract for minimal autonomous agents.
 */
abstract class MinimalAutonomousAgent(
    private val runLLMToPerceive: (perception: Perception) -> Idea,
    private val runLLMToPlan: (ideas: List<Idea>) -> Plan,
    private val runLLMToExecuteTask: (task: Task) -> Outcome,
    private val runLLMToExecuteTool: (tool: Tool, parameters: Map<String, Any?>) -> Outcome,
    private val runLLMToEvaluate: (outcomes: List<Outcome>) -> Idea,
    agentConfiguration: AgentConfiguration,
) : MinimalAgent(agentConfiguration) {

    // ==================== Agent State ====================

    private var agentRunning = false
    private var agentScope: CoroutineScope? = null
    private var runtimeLoopJob: Job? = null

    // ==================== Agent Metadata ====================

    /** Unique identifier for this agent */
    abstract override val id: AgentId

    /** Set of tools that this agent requires to execute its actions */
    open val requiredTools: Set<Tool> = emptySet()

    // ==================== Agent Runtime ====================

    protected suspend fun runtimeLoop() {
        while (agentRunning) {
            val previousIdea = getCurrentState().currentIdea

            val idea = perceiveState(previousIdea)
            rememberIdea(idea)

            val plan = planIdea(idea)
            rememberPlan(plan)

            val outcome = executePlan(plan)
            rememberOutcome(outcome)

            val nextIdea = evaluateNewIdeas(outcome)
            rememberIdea(nextIdea)

            delay(1.seconds)
        }
    }

    fun initialize(scope: CoroutineScope) {
        agentRunning = true
        agentScope = scope
        runtimeLoopJob = scope.launch {
            runtimeLoop()
        }
    }

    fun pauseAgent() {
        agentRunning = false
        runtimeLoopJob?.cancel()
        runtimeLoopJob = null
        resetWorkingMemory()
    }

    fun resumeAgent() {
        agentRunning = true
        runtimeLoopJob = agentScope?.launch {
            runtimeLoop()
        }
    }

    fun shutdownAgent() {
        pauseAgent()
        resetAllMemory()
    }

    // ==================== Agent Actions ====================

    /** Reads and interprets the current world state */
    override suspend fun perceiveState(
        vararg newIdeas: Idea,
    ): Idea {
        val statePerception = Perception(
            ideas = newIdeas.toList(),
            currentState = getCurrentState(),
            timestamp = Clock.System.now(),
        )
        rememberPerception(statePerception)

        val idea = runLLMToPerceive(statePerception)
        return idea
    }

    /** Breaks down a complex task into smaller steps */
    override suspend fun planIdea(
        vararg ideas: Idea,
    ): Plan {
        val plan = runLLMToPlan(ideas.toList())
        rememberPlan(plan)
        return plan
    }

    /** Executes a plan */
    override suspend fun executePlan(
        plan: Plan,
    ): Outcome =
        plan.tasks.map { task ->
            rememberTask(task)
            val outcome = runLLMToExecuteTask(task)
            rememberOutcome(outcome)
            outcome
        }.reduce { runningOutcome, outcome ->
            if (runningOutcome !is Outcome.Success) {
                runningOutcome
            } else {
                outcome
            }
        }

    override suspend fun runTask(task: Task): Outcome {
        val outcome = runLLMToExecuteTask(task)
        rememberOutcome(outcome)
        return outcome
    }

    override suspend fun runTool(tool: Tool, parameters: Map<String, Any?>): Outcome {
        val outcome = runLLMToExecuteTool(tool, parameters)
        rememberOutcome(outcome)
        return outcome
    }

    /** Evaluates the current state of the world and determines the best action to take next */
    override suspend fun evaluateNewIdeas(
        vararg outcomes: Outcome,
    ): Idea {
        val idea = runLLMToEvaluate(outcomes.toList())
        rememberIdea(idea)
        return idea
    }
}
