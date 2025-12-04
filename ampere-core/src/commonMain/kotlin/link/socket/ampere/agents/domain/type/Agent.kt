package link.socket.ampere.agents.domain.type

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.domain.AgentError
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.IdeaId
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.PerceptionId
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.PlanId
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.outcome.OutcomeId
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.concept.task.TaskId
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.util.logWith

typealias AgentId = String

@Serializable
sealed interface Agent <S : AgentState> {

    val id: AgentId
    val initialState: S
    val agentConfiguration: AgentConfiguration

    /**
     * Optional memory service for persistent knowledge storage and retrieval.
     * When null, the agent operates without long-term memory recall capabilities.
     * When provided, enables episodic memory queries across sessions.
     */
    @Transient
    val memoryService: AgentMemoryService?
        get() = null

    private val logger: Logger
        get() = logWith("Agent/$id")

    suspend fun perceiveState(
        currentState: S,
        vararg newIdeas: Idea,
    ): Perception<S>

    /**
     * Determine a plan for executing the given task.
     *
     * This method is now enhanced with episodic memory—agents can incorporate
     * learnings from past similar tasks to inform their planning.
     *
     * @param task The task to plan for
     * @param ideas Current ideas informing the plan
     * @param relevantKnowledge Past knowledge entries relevant to this task context
     * @return A plan incorporating both current ideas and past learnings
     */
    suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore> = emptyList()
    ): Plan

    suspend fun executePlan(plan: Plan): Outcome
    suspend fun runTask(task: Task): Outcome
    suspend fun runTool(tool: Tool<*>, request: ExecutionRequest<*>): ExecutionOutcome
    suspend fun evaluateNextIdeaFromOutcomes(vararg outcomes: Outcome): Idea

    /**
     * Extract knowledge from a completed task outcome.
     *
     * This is where the agent reflects on what it learned: "What approach did I use?
     * What worked well or failed? What would I do differently next time?"
     *
     * The extracted knowledge will be stored in long-term memory for future recall
     * when facing similar tasks.
     *
     * @param outcome The completed task outcome to learn from
     * @param task The task that was executed
     * @param plan The plan that was followed
     * @return Knowledge capturing the approach and learnings from this execution
     */
    fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan
    ): Knowledge

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
    suspend fun recallRelevantKnowledge(
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
    suspend fun storeKnowledge(
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

    private val _stateFlow: MutableStateFlow<S>
        get() = MutableStateFlow(initialState)

    val stateFlow: StateFlow<S>
        get() = _stateFlow.asStateFlow()

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

    fun rememberNewIdea(idea: Idea) {
        val currentState = getCurrentState()
        currentState.setNewIdea(idea)
        _stateFlow.value = currentState
    }

    fun rememberNewOutcome(outcome: Outcome) {
        val currentState = getCurrentState()
        currentState.setNewOutcome(outcome)
        _stateFlow.value = currentState
    }

    fun rememberNewPerception(perception: Perception<*>) {
        val currentState = getCurrentState()
        currentState.setNewPerception(perception)
        _stateFlow.value = currentState
    }

    fun rememberNewPlan(plan: Plan) {
        val currentState = getCurrentState()
        currentState.setNewPlan(plan)
        _stateFlow.value = currentState
    }

    fun rememberNewTask(task: Task) {
        val currentState = getCurrentState()
        currentState.setNewTask(task)
        _stateFlow.value = currentState
    }

    fun finishCurrentIdea() {
        val currentState = getCurrentState()
        currentState.setNewIdea(Idea.blank)
        _stateFlow.value = currentState
    }

    fun finishCurrentOutcome() {
        val currentState = getCurrentState()
        currentState.setNewOutcome(Outcome.blank)
        _stateFlow.value = currentState
    }

    fun finishCurrentPerception() {
        val currentState = getCurrentState()
        currentState.setNewPerception(Perception.blank)
    }

    fun finishCurrentPlan() {
        val currentState = getCurrentState()
        currentState.setNewPlan(Plan.blank)
        _stateFlow.value = currentState
    }

    fun finishCurrentTask() {
        val currentState = getCurrentState()
        currentState.setNewTask(Task.blank)
        _stateFlow.value = currentState
    }

    fun resetCurrentMemory() {
        val currentState = getCurrentState()
        currentState.resetCurrentMemoryCell()
        _stateFlow.value = currentState
    }

    fun resetPastMemory() {
        val currentState = getCurrentState()
        currentState.resetPastMemoryCell()
        _stateFlow.value = currentState
    }

    fun resetAllMemory() {
        resetCurrentMemory()
        resetPastMemory()
    }
}
