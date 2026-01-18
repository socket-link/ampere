package link.socket.ampere.agents.definition

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.SparkStack
import link.socket.ampere.agents.domain.cognition.ToolId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.cognition.sparks.TaskSpark
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Contract for autonomous agents.
 *
 * AutonomousAgent now supports dynamic cognitive context through the Spark system.
 * Each agent has a [CognitiveAffinity] that shapes how it approaches problems,
 * and a [SparkStack] that accumulates specialization layers.
 *
 * The system prompt is dynamically built from the SparkStack before each LLM
 * interaction, allowing agents to specialize their behavior without requiring
 * separate class implementations.
 */
@Serializable
abstract class AutonomousAgent<S : AgentState> : Agent<S>, NeuralAgent<S> {

    // ==================== Agent Metadata ====================

    /** Unique identifier for this agent */
    abstract override val id: AgentId

    /** Set of tools that this agent requires to execute its actions */
    open val requiredTools: Set<Tool<*>> = emptySet()

    // ==================== Cognitive Context (Spark System) ====================

    /**
     * The cognitive affinity for this agent.
     *
     * Affinity shapes HOW the agent thinks about problems - it's the "elemental type"
     * chosen at agent creation. Subclasses should override this to specify their
     * default affinity.
     *
     * Default is INTEGRATIVE as it provides balanced problem-solving approach.
     */
    @Transient
    open val affinity: CognitiveAffinity = CognitiveAffinity.INTEGRATIVE

    /**
     * The cognitive context stack for this agent.
     *
     * Sparks are pushed onto this stack to specialize the agent's context.
     * The stack is initialized lazily from the affinity (to handle subclass overrides)
     * and can be modified through [spark] and [unspark] methods.
     */
    @Transient
    protected var sparkStack: SparkStack = uninitializedSparkStack
        private set

    // Lazy initialization backing field - will be properly initialized on first access
    @Transient
    private var sparkStackInitialized: Boolean = false

    /**
     * Ensures the spark stack is properly initialized with the (possibly overridden) affinity.
     * This is called lazily on first access to handle Kotlin's initialization order.
     */
    protected fun ensureSparkStackInitialized() {
        if (!sparkStackInitialized) {
            sparkStack = SparkStack.withAffinity(affinity)
            sparkStackInitialized = true
        }
    }

    companion object {
        // Placeholder for uninitialized state - will be replaced on first access
        private val uninitializedSparkStack = SparkStack.withAffinity(CognitiveAffinity.INTEGRATIVE)
    }

    /**
     * Pushes a Spark onto the cognitive context stack.
     *
     * This specializes the agent's context by adding a new layer. The system
     * prompt will include this Spark's contribution, and tool/file access may
     * be narrowed.
     *
     * @param spark The Spark to push onto the stack
     * @return This agent for fluent chaining
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : AutonomousAgent<S>> spark(spark: Spark): T {
        ensureSparkStackInitialized()
        sparkStack = sparkStack.push(spark)
        onSparkApplied(spark)
        return this as T
    }

    /**
     * Pops the top Spark from the cognitive context stack.
     *
     * This returns the agent to a broader context by removing the most recent
     * specialization layer.
     *
     * @return true if a Spark was removed, false if the stack was empty
     */
    fun unspark(): Boolean {
        ensureSparkStackInitialized()
        val previousSpark = sparkStack.peek()
        val newStack = sparkStack.pop()
        return if (newStack != null) {
            sparkStack = newStack
            onSparkRemoved(previousSpark)
            true
        } else {
            false
        }
    }

    /**
     * Reinitializes the spark stack from the current affinity.
     *
     * Call this after changing the affinity to reset the stack.
     */
    protected fun reinitializeSparkStack() {
        sparkStack = SparkStack.withAffinity(affinity)
    }

    /**
     * The current system prompt, dynamically built from the SparkStack.
     *
     * This should be used when making LLM calls to provide the agent with
     * its full cognitive context.
     */
    val currentSystemPrompt: String
        get() {
            ensureSparkStackInitialized()
            return sparkStack.buildSystemPrompt()
        }

    /**
     * The effective set of allowed tools given current Spark constraints.
     *
     * Returns null if no Sparks constrain tools (all tools available).
     * When filtering tools for LLM calls, intersect with [requiredTools].
     */
    val availableTools: Set<ToolId>?
        get() {
            ensureSparkStackInitialized()
            return sparkStack.effectiveAllowedTools()
        }

    /**
     * The effective file access scope given current Spark constraints.
     */
    val effectiveFileAccess: FileAccessScope
        get() {
            ensureSparkStackInitialized()
            return sparkStack.effectiveFileAccess()
        }

    /**
     * Human-readable description of the current cognitive state.
     *
     * Format: [AFFINITY] → [Spark1] → [Spark2] → ...
     */
    val cognitiveState: String
        get() {
            ensureSparkStackInitialized()
            return sparkStack.describe()
        }

    /**
     * The depth of the current Spark stack.
     */
    val sparkDepth: Int
        get() {
            ensureSparkStackInitialized()
            return sparkStack.depth
        }

    /**
     * Hook called after a Spark is pushed onto the stack.
     *
     * Subclasses can override this to emit events or perform other actions
     * when the cognitive context changes.
     *
     * @param spark The Spark that was just applied
     */
    protected open fun onSparkApplied(spark: Spark) {
        // Default: no-op. Override in subclasses for event emission.
    }

    /**
     * Hook called after a Spark is popped from the stack.
     *
     * Subclasses can override this to emit events or perform other actions
     * when the cognitive context changes.
     *
     * @param previousSpark The Spark that was just removed, or null if unknown
     */
    protected open fun onSparkRemoved(previousSpark: Spark?) {
        // Default: no-op. Override in subclasses for event emission.
    }

    // ==================== Agent State ====================

    override fun rememberNewTask(task: Task) {
        super<Agent>.rememberNewTask(task)

        if (task is Task.Blank) {
            return
        }

        applyTaskSparkIfMissing(task)
    }

    override fun finishCurrentTask() {
        val currentTask = getCurrentState().getCurrentMemory().task
        if (currentTask !is Task.Blank) {
            removeTaskSpark(currentTask.id)
        }
        super<Agent>.finishCurrentTask()
    }

    private var agentIsRunning = false
    private var agentRuntimeScope: CoroutineScope? = null
    private var agentRuntimeLoopJob: Job? = null
    @Transient
    private val phaseSparkManager: PhaseSparkManager<S> by lazy(LazyThreadSafetyMode.NONE) {
        PhaseSparkManager.create(this)
    }

    // ==================== Agent Runtime ====================

    protected suspend fun runtimeLoop() {
        while (agentIsRunning) {
            val currentTask = getCurrentState().getCurrentMemory().task

            val previousIdea = getCurrentState().getCurrentMemory().idea

            val statePerception = phaseSparkManager.withPhase(CognitivePhase.PERCEIVE) {
                perceiveState(
                    currentState = getCurrentState(),
                    newIdeas = listOf(previousIdea).toTypedArray(),
                )
            }
            rememberNewPerception(statePerception)

            // Recall relevant knowledge from past similar tasks
            val relevantKnowledge = recallRelevantKnowledgeForTask(currentTask)

            relevantKnowledge.map { knowledge ->
                knowledge.knowledge.learnings
            }

            val plan = phaseSparkManager.withPhase(CognitivePhase.PLAN) {
                determinePlanForTask(
                    task = currentTask,
                    relevantKnowledge = relevantKnowledge,
                    ideas = statePerception.ideas.toTypedArray(),
                )
            }
            rememberNewPlan(plan)

            val outcome = phaseSparkManager.withPhase(CognitivePhase.EXECUTE) {
                executePlan(plan)
            }
            rememberNewOutcome(outcome)

            phaseSparkManager.withPhase(CognitivePhase.LEARN) {
                // Extract and store knowledge from this execution
                extractAndStoreKnowledge(outcome, currentTask, plan)

                val nextIdea = evaluateNextIdeaFromOutcomes(outcome)
                rememberNewIdea(nextIdea)
            }

            delay(1.seconds)
        }
    }

    /**
     * Recall relevant knowledge for the current task context.
     *
     * Queries the agent's long-term memory for past experiences with similar tasks.
     * Returns an empty list if memory service is unavailable or recall fails.
     */
    private suspend fun recallRelevantKnowledgeForTask(task: Task): List<KnowledgeWithScore> {
        // Build context from the task description
        val context = when (task) {
            is Task.CodeChange -> MemoryContext(
                taskType = "code_change",
                tags = emptySet(),
                description = task.description,
            )
            else -> MemoryContext(
                taskType = "generic",
                tags = emptySet(),
                description = "Generic task: ${task.id}",
            )
        }

        return recallRelevantKnowledge(context, limit = 10)
            .getOrElse { emptyList<KnowledgeWithScore>() }
    }

    /**
     * Extract knowledge from completed outcome and store for future recall.
     *
     * This closes the learning loop—capturing what the agent learned from this
     * execution for use in future similar tasks.
     */
    private suspend fun extractAndStoreKnowledge(outcome: Outcome, task: Task, plan: Plan) {
        try {
            // Extract knowledge using the agent's domain-specific logic
            val knowledge = extractKnowledgeFromOutcome(outcome, task, plan)

            // Determine tags and task type for better retrieval
            val tags = mutableListOf<String>()
            val taskType = when (task) {
                is Task.CodeChange -> {
                    tags.add("code")
                    "code_change"
                }
                else -> "generic"
            }

            when (outcome) {
                is Outcome.Success -> tags.add("success")
                is Outcome.Failure -> tags.add("failure")
                else -> tags.add("partial")
            }

            // Store in long-term memory
            storeKnowledge(knowledge, tags = tags, taskType = taskType)
        } catch (e: Exception) {
            // Logging is handled in storeKnowledge, just catch to prevent loop crash
        }
    }

    fun initialize(scope: CoroutineScope) {
        agentIsRunning = true
        agentRuntimeScope = scope
        agentRuntimeLoopJob = scope.launch {
            runtimeLoop()
        }
    }

    fun pauseAgent() {
        agentIsRunning = false
        agentRuntimeLoopJob?.cancel()
        agentRuntimeLoopJob = null
        resetCurrentMemory()
    }

    fun resumeAgent() {
        agentIsRunning = true
        agentRuntimeLoopJob = agentRuntimeScope?.launch {
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
        currentState: S,
        vararg newIdeas: Idea,
    ): Perception<S> {
        val perception = Perception(
            id = generateUUID(id),
            ideas = newIdeas.toList(),
            currentState = currentState,
            timestamp = Clock.System.now(),
        )
        rememberNewPerception(perception)

        val ideaAboutPerception = runLLMToEvaluatePerception(perception)
        rememberNewIdea(ideaAboutPerception)

        return Perception(
            id = perception.id,
            ideas = listOf(ideaAboutPerception),
            currentState = currentState,
            timestamp = Clock.System.now(),
        )
    }

    /** Breaks down a complex task into smaller tasks, informed by past knowledge */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>,
    ): Plan {
        // Note: AutonomousAgent implementations should override this to incorporate
        // relevantKnowledge into their planning. The base implementation here
        // ignores knowledge for backwards compatibility.
        val plan = runLLMToPlan(task, ideas.toList())
        rememberNewPlan(plan)
        return plan
    }

    /**
     * Executes a plan of actions with automatic TaskSpark lifecycle management.
     *
     * **Ticket #228/#229**: Each task in the plan receives its own TaskSpark
     * that is applied before execution and removed after completion. This
     * provides task-specific context during execution.
     *
     * @param plan The plan containing tasks to execute
     * @return The combined outcome of all tasks in the plan
     */
    override suspend fun executePlan(
        plan: Plan,
    ): Outcome {
        if (plan.tasks.isEmpty()) {
            return Outcome.blank
        }

        return plan.tasks.map { task ->
            rememberNewTask(task)
            executeTaskWithSpark(task)
        }.reduce { runningOutcome, outcome ->
            if (runningOutcome !is Outcome.Success) {
                runningOutcome
            } else {
                outcome
            }
        }
    }

    /**
     * Executes a single task with TaskSpark lifecycle management.
     *
     * This internal method applies a TaskSpark before task execution
     * and ensures it's removed afterward.
     *
     * @param task The task to execute
     * @return The outcome of the task execution
     */
    private suspend fun executeTaskWithSpark(task: Task): Outcome {
        // Skip TaskSpark for blank tasks
        if (task is Task.Blank) {
            return Outcome.blank
        }

        // Apply TaskSpark before execution (if not already assigned)
        val taskSpark = TaskSpark.fromTask(task)
        val preSparkDepth = sparkDepth
        val hadTaskSpark = findTaskSpark(taskSpark.taskId) != null
        if (!hadTaskSpark) {
            spark<AutonomousAgent<S>>(taskSpark)
        }

        return try {
            val outcome = runLLMToExecuteTask(task)
            rememberNewOutcome(outcome)
            outcome
        } finally {
            // Ensure TaskSpark is removed even during cancellation
            withContext(NonCancellable) {
                removeTaskSpark(taskSpark.taskId)
                if (sparkDepth > preSparkDepth) {
                    // Multiple TaskSparks may have been added (nested tasks)
                    // Only remove ours - nested tasks should manage their own
                }
            }
        }
    }

    /**
     * Executes a task from a plan with automatic TaskSpark lifecycle management.
     *
     * **Ticket #228/#229**: This method automatically applies a TaskSpark when
     * task execution begins and removes it when execution completes (success,
     * failure, or cancellation).
     *
     * The TaskSpark provides task-specific context to the agent's cognitive
     * stack during execution, and is guaranteed to be removed via the finally
     * block with NonCancellable context.
     *
     * @param task The task to execute
     * @return The outcome of the task execution
     */
    override suspend fun runTask(task: Task): Outcome {
        // Skip TaskSpark for blank tasks
        if (task is Task.Blank) {
            return Outcome.blank
        }

        rememberNewTask(task)
        return executeTaskWithSpark(task)
    }

    /** Executes a tool with the given parameters */
    override suspend fun runTool(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        val outcome = runLLMToExecuteTool(tool, request)
        rememberNewOutcome(outcome)
        return outcome
    }

    /** Evaluates the current state of the world and determines the best action to take next */
    override suspend fun evaluateNextIdeaFromOutcomes(
        vararg outcomes: Outcome,
    ): Idea {
        val idea = runLLMToEvaluateOutcomes(outcomes.toList())
        rememberNewIdea(idea)
        return idea
    }

    private fun applyTaskSparkIfMissing(task: Task) {
        val taskId = task.id
        if (findTaskSpark(taskId) != null) {
            return
        }
        val taskSpark = TaskSpark.fromTask(task)
        spark<AutonomousAgent<S>>(taskSpark)
    }

    private fun findTaskSpark(taskId: TaskId): TaskSpark? {
        ensureSparkStackInitialized()
        return sparkStack.sparks
            .filterIsInstance<TaskSpark>()
            .lastOrNull { it.taskId == taskId }
    }

    private fun removeTaskSpark(taskId: TaskId): TaskSpark? {
        ensureSparkStackInitialized()

        val currentTop = sparkStack.peek()
        if (currentTop is TaskSpark && currentTop.taskId == taskId) {
            unspark<AutonomousAgent<S>>()
            return currentTop
        }

        val sparks = sparkStack.sparks
        val index = sparks.indexOfLast { it is TaskSpark && it.taskId == taskId }
        if (index == -1) {
            return null
        }

        val removed = sparks[index] as TaskSpark
        val newSparks = sparks.toMutableList().also { it.removeAt(index) }
        var newStack = SparkStack.withAffinity(affinity)
        newSparks.forEach { spark -> newStack = newStack.push(spark) }
        sparkStack = newStack
        onSparkRemoved(removed)
        return removed
    }
}
