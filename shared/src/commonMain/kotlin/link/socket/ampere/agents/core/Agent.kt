package link.socket.ampere.agents.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.core.memory.AgentMemoryService
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.memory.MemoryContext
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
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.util.logWith

typealias AgentId = String

@Serializable
sealed class Agent <S : AgentState> {

    abstract val id: AgentId
    abstract val initialState: S
    abstract val agentConfiguration: AgentConfiguration

    /**
     * Optional memory service for persistent knowledge storage and retrieval.
     * When null, the agent operates without long-term memory recall capabilities.
     * When provided, enables episodic memory queries across sessions.
     */
    @Transient
    protected open val memoryService: AgentMemoryService? = null

    @Transient
    private val logger by lazy { logWith("Agent/$id") }

    abstract suspend fun perceiveState(vararg newIdeas: Idea): Idea
    abstract suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore> = emptyList()
    ): Plan
    abstract suspend fun executePlan(plan: Plan): Outcome
    abstract suspend fun runTask(task: Task): Outcome
    abstract suspend fun runTool(tool: Tool<*>, request: ExecutionRequest<*>): ExecutionOutcome
    abstract suspend fun evaluateNextIdeaFromOutcomes(vararg outcomes: Outcome): Idea

    /**
     * Recall relevant past knowledge based on current context.
     *
     * This is episodic memory retrieval—querying accumulated learnings for
     * entries relevant to the current situation. Returned knowledge informs
     * planning by showing what approaches worked or failed in similar contexts.
     *
     * This complements the in-memory AgentState.getPastMemory() with long-term
     * persistent recall across agent restarts and sessions.
     *
     * @param context Current situation to find relevant memories for
     * @param limit Maximum knowledge entries to retrieve (default 10)
     * @return Result containing knowledge entries ranked by relevance, or an error
     */
    protected suspend fun recallRelevantKnowledge(
        context: MemoryContext,
        limit: Int = 10
    ): Result<List<KnowledgeWithScore>> {
        // Check if memory service is available
        val service = memoryService
        if (service == null) {
            logger.w { "Memory service not available - operating without long-term memory recall" }
            return Result.failure(
                AgentError.MemoryRecallFailure(
                    message = "Memory service not configured for this agent",
                    cause = null
                )
            )
        }

        return try {
            // Query the persistent memory service
            val recallResult = service.recallRelevantKnowledge(
                context = context,
                limit = limit
            )

            recallResult.fold(
                onSuccess = { scoredKnowledge ->
                    logger.i {
                        "Recalled ${scoredKnowledge.size} relevant knowledge entries " +
                        "with average relevance ${
                            if (scoredKnowledge.isNotEmpty())
                                scoredKnowledge.map { it.relevanceScore }.average()
                            else 0.0
                        }"
                    }
                    Result.success(scoredKnowledge)
                },
                onFailure = { error ->
                    logger.w { "Knowledge recall failed: ${error.message}" }
                    Result.failure(
                        AgentError.MemoryRecallFailure(
                            message = "Could not retrieve relevant knowledge: ${error.message}",
                            cause = error
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.e(e) { "Exception during knowledge recall" }
            Result.failure(
                AgentError.MemoryRecallFailure(
                    message = "Exception during knowledge recall: ${e.message}",
                    cause = e
                )
            )
        }
    }

    /**
     * Store knowledge from a completed experience.
     *
     * This persists learnings for future recall, building the agent's
     * long-term episodic memory. Should be called after extracting
     * knowledge from cognitive elements (outcomes, plans, etc.).
     *
     * @param knowledge The knowledge to persist
     * @param tags Optional tags for categorization
     * @param taskType Optional task type for context-based retrieval
     * @return Result containing the stored knowledge entry or an error
     */
    protected suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null
    ): Result<Unit> {
        val service = memoryService
        if (service == null) {
            logger.w { "Memory service not available - cannot store knowledge" }
            return Result.failure(
                AgentError.MemoryRecallFailure(
                    message = "Memory service not configured for this agent",
                    cause = null
                )
            )
        }

        return try {
            service.storeKnowledge(
                knowledge = knowledge,
                tags = tags,
                taskType = taskType
            ).fold(
                onSuccess = { entry ->
                    logger.i { "Stored knowledge entry ${entry.id} of type ${entry.knowledgeType}" }

                    // Also add to in-memory state for immediate access
                    when (knowledge) {
                        is Knowledge.FromIdea -> getCurrentState().addToPastKnowledge(
                            rememberedKnowledgeFromIdeas = listOf(knowledge)
                        )
                        is Knowledge.FromOutcome -> getCurrentState().addToPastKnowledge(
                            rememberedKnowledgeFromOutcomes = listOf(knowledge)
                        )
                        is Knowledge.FromPerception -> getCurrentState().addToPastKnowledge(
                            rememberedKnowledgeFromPerceptions = listOf(knowledge)
                        )
                        is Knowledge.FromPlan -> getCurrentState().addToPastKnowledge(
                            rememberedKnowledgeFromPlans = listOf(knowledge)
                        )
                        is Knowledge.FromTask -> getCurrentState().addToPastKnowledge(
                            rememberedKnowledgeFromTasks = listOf(knowledge)
                        )
                    }

                    Result.success(Unit)
                },
                onFailure = { error ->
                    logger.w { "Knowledge storage failed: ${error.message}" }
                    Result.failure(
                        AgentError.MemoryRecallFailure(
                            message = "Could not store knowledge: ${error.message}",
                            cause = error
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.e(e) { "Exception during knowledge storage" }
            Result.failure(
                AgentError.MemoryRecallFailure(
                    message = "Exception during knowledge storage: ${e.message}",
                    cause = e
                )
            )
        }
    }

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

/**
 * Agent-specific errors.
 *
 * These errors represent failures in agent cognitive processes, including
 * memory operations, planning failures, and execution errors.
 */
sealed class AgentError : Exception() {

    /**
     * Error during memory recall or storage operations.
     *
     * This can occur when:
     * - Memory service is not configured
     * - Database queries fail
     * - Knowledge retrieval/storage encounters errors
     *
     * @param message Human-readable error description
     * @param cause The underlying cause (can be Exception or other error types)
     */
    data class MemoryRecallFailure(
        override val message: String,
        override val cause: Throwable? = null
    ) : AgentError() {
        // Secondary constructor for non-Throwable causes
        constructor(message: String, cause: Any?) : this(
            message = message,
            cause = cause as? Throwable
        )
    }
}
