package link.socket.ampere.agents.definition

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.SparkStack
import link.socket.ampere.agents.domain.cognition.ToolId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.cognition.sparks.SparkSelectionContext
import link.socket.ampere.agents.domain.cognition.sparks.TaskSpark
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.describeResult
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.tools.registry.ToolRegistry

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

    /**
     * Optional reference to the ToolRegistry for dynamic tool discovery.
     *
     * When set, the agent can perceive dynamically discovered tools (including MCP tools)
     * during the PERCEIVE phase of the cognitive loop. This enables agents to be aware of
     * tools that are registered at runtime, not just their statically-defined [requiredTools].
     *
     * Set this after agent creation (e.g., during system initialization) via
     * [link.socket.ampere.startup.AmpereStartupResult.registry].
     */
    @Transient
    var toolRegistry: ToolRegistry? = null

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

        /**
         * How long [runtimeLoop] waits between iterations, whether it ran a
         * cognitive cycle or idled for want of an assignment.
         */
        private val RUNTIME_LOOP_INTERVAL = 1.seconds
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
     * The cognitive phase the agent is currently executing in, or null when no
     * phase is active. Updated by [PhaseSparkManager] on phase entry/exit so the
     * spark stack can include per-phase contributions in the assembled prompt.
     */
    @Transient
    internal var currentCognitivePhase: CognitivePhase? = null

    /**
     * The current system prompt, dynamically built from the SparkStack.
     *
     * Includes per-phase Spark contributions corresponding to [currentCognitivePhase]
     * when set. Used when making LLM calls so the agent's cognitive context reflects
     * both the role-level sparks and the currently-active phase guidance.
     */
    val currentSystemPrompt: String
        get() {
            ensureSparkStackInitialized()
            return sparkStack.buildSystemPrompt(currentCognitivePhase)
        }

    /**
     * The effective set of allowed tool *ids* given current Spark constraints.
     *
     * Returns null if no Sparks constrain tools (all tools available). This is
     * the raw permission set — a spark may name an id the agent was never built
     * with. Use [effectiveTools] for the tools the agent can actually dispatch.
     */
    val availableTools: Set<ToolId>?
        get() {
            ensureSparkStackInitialized()
            return sparkStack.effectiveAllowedTools()
        }

    /**
     * The tools this agent may actually act with: [requiredTools] narrowed by
     * [availableTools].
     *
     * A null [availableTools] means no spark on the stack constrains tools, so
     * the full [requiredTools] set is effective. This is the set to offer a
     * planner and the set to dispatch against — reading [requiredTools] directly
     * at either point ignores the spark stack's narrowing entirely (AMPR-400).
     *
     * Read it live at every use rather than capturing it once. The stack is
     * mutable for the life of the agent ([spark] / [unspark]), so a captured set
     * would keep offering tools a later spark has since withdrawn.
     */
    val effectiveTools: Set<Tool<*>>
        get() {
            val allowed = availableTools ?: return requiredTools
            return requiredTools.filterTo(mutableSetOf<Tool<*>>()) { it.id in allowed }
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
     * Format: `[AFFINITY] → [Spark1] → [Spark2] → ...`
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

    // Lazy because [initialState] is typically a subclass constructor property,
    // which is not yet assigned while this base class initializes.
    private val stateRevisions: MutableStateFlow<StateRevision<S>> by lazy {
        MutableStateFlow(StateRevision(initialState))
    }

    final override val stateFlow: StateFlow<S> by lazy {
        RevisionedStateFlow(stateRevisions.asStateFlow())
    }

    private inline fun updateState(update: S.() -> Unit) {
        val currentState = getCurrentState()
        currentState.update()
        // The state is mutated in place, so re-assigning the same instance would be
        // conflated away; a fresh revision guarantees collectors see every update.
        stateRevisions.value = StateRevision(currentState)
    }

    override fun rememberNewIdea(idea: Idea) = updateState { setNewIdea(idea) }

    override fun rememberNewOutcome(outcome: Outcome) = updateState { setNewOutcome(outcome) }

    override fun rememberNewPerception(perception: Perception<*>) = updateState { setNewPerception(perception) }

    override fun rememberNewPlan(plan: Plan) = updateState { setNewPlan(plan) }

    override fun rememberNewTask(task: Task) {
        updateState { setNewTask(task) }

        if (task is Task.Blank) {
            return
        }

        applyTaskSparkIfMissing(task)
    }

    override fun finishCurrentIdea() = updateState { setNewIdea(Idea.blank) }

    override fun finishCurrentOutcome() = updateState { setNewOutcome(Outcome.blank) }

    override fun finishCurrentPerception() = updateState { setNewPerception(Perception.blank) }

    override fun finishCurrentPlan() = updateState { setNewPlan(Plan.blank) }

    override fun finishCurrentTask() {
        val currentTask = getCurrentState().getCurrentMemory().task
        if (currentTask !is Task.Blank) {
            removeTaskSpark(currentTask.id)
        }
        updateState { setNewTask(Task.blank) }
    }

    override fun resetCurrentMemory() = updateState { resetCurrentMemoryCell() }

    override fun resetPastMemory() = updateState { resetPastMemoryCell() }

    private var agentIsRunning = false
    private var agentRuntimeScope: CoroutineScope? = null
    private var agentRuntimeLoopJob: Job? = null

    private val phaseSparkManager: PhaseSparkManager<S> by lazy(LazyThreadSafetyMode.NONE) {
        createPhaseSparkManager()
    }

    /**
     * Hook for subclasses to inject a [link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary]
     * (or other manager configuration) when the phase-spark manager is first
     * constructed. Default implementation builds a manager without a library —
     * preserving pre-existing single-built-in-spark behavior.
     */
    protected open fun createPhaseSparkManager(): PhaseSparkManager<S> =
        PhaseSparkManager.create(
            agent = this,
            phaseConfig = agentConfiguration.cognitiveConfig.phaseSparks,
            eventApi = phaseSparkEventApi,
        )

    /**
     * The door the phase-spark manager publishes `PhaseEntered` / `PhaseExited` through
     * (F1, AMPR-339). Null here — the base agent has no door; [ObservableAgent] supplies its own.
     */
    protected open val phaseSparkEventApi: AgentEventApi?
        get() = null

    private fun taskTextFor(task: Task): String {
        if (task is Task.Blank) return ""
        val codeDescription = (task as? Task.CodeChange)?.description
        return codeDescription ?: task.toString()
    }

    // ==================== Agent Runtime ====================

    /**
     * The agent's cognitive cycle, one PROPEL pass per iteration over whatever task
     * the agent has been assigned.
     *
     * An iteration only runs when there *is* an assignment: an agent whose current
     * task is [Task.Blank] has nothing to think about, so it idles without spending
     * a model call and waits for the next assignment to arrive through
     * [rememberNewTask]. Each iteration finishes the task it ran, because
     * [executePlan] records every plan step as the current task and the plan's last
     * step must not become the next iteration's assignment.
     */
    protected suspend fun runtimeLoop() {
        while (agentIsRunning) {
            val currentTask = getCurrentState().getCurrentMemory().task

            if (currentTask is Task.Blank) {
                // No assignment: idle rather than reason about nothing.
                delay(RUNTIME_LOOP_INTERVAL)
                continue
            }

            val previousIdea = getCurrentState().getCurrentMemory().idea

            // Gather dynamic tool awareness from the registry
            val perceptionIdeas = buildList {
                add(previousIdea)
                buildToolAwarenessIdea()?.let { add(it) }
            }

            val taskText = taskTextFor(currentTask)
            val statePerception = phaseSparkManager.withPhase(
                CognitivePhase.PERCEIVE,
                SparkSelectionContext(phase = CognitivePhase.PERCEIVE, text = taskText),
            ) {
                perceiveState(
                    currentState = getCurrentState(),
                    newIdeas = perceptionIdeas.toTypedArray(),
                )
            }
            rememberNewPerception(statePerception)

            // Recall relevant knowledge from past similar tasks
            val relevantKnowledge = recallRelevantKnowledgeForTask(currentTask)

            val plan = phaseSparkManager.withPhase(
                CognitivePhase.PLAN,
                SparkSelectionContext(phase = CognitivePhase.PLAN, text = taskText),
            ) {
                determinePlanForTask(
                    task = currentTask,
                    relevantKnowledge = relevantKnowledge,
                    ideas = statePerception.ideas.toTypedArray(),
                )
            }
            rememberNewPlan(plan)

            val outcome = phaseSparkManager.withPhase(
                CognitivePhase.EXECUTE,
                SparkSelectionContext(phase = CognitivePhase.EXECUTE, text = taskText),
            ) {
                executePlan(plan)
            }
            rememberNewOutcome(outcome)

            phaseSparkManager.withPhase(
                CognitivePhase.LEARN,
                SparkSelectionContext(phase = CognitivePhase.LEARN, text = taskText),
            ) {
                // Extract and store knowledge from this execution
                extractAndStoreKnowledge(outcome, currentTask, plan)

                val nextIdea = evaluateNextIdeaFromOutcomes(outcome)
                rememberNewIdea(nextIdea)
            }

            // Close out the assignment. Without this the current task is whatever
            // plan step [executePlan] recorded last, and the next iteration would
            // read the plan's own output back as its assignment and re-plan it.
            finishCurrentTask()
            // [finishCurrentTask] releases the plan step's spark; the assignment's
            // own spark, pushed when it was remembered, is ours to release.
            removeTaskSpark(currentTask.id)

            delay(RUNTIME_LOOP_INTERVAL)
        }
    }

    /**
     * Recall relevant knowledge for the current task context.
     *
     * Queries the agent's long-term memory for past experiences with similar tasks.
     * Returns an empty list if memory service is unavailable or recall fails.
     *
     * `protected` rather than private because the Recall step is not unique to
     * [runtimeLoop]: a subclass that generates a Plan at another seam — e.g.
     * `SparkBasedAgent.runSubPlanForTask`, which re-plans one coarse step into a
     * sub-plan — owes that Plan a Recall too, and recalls through here so there is
     * one definition of what a task's [MemoryContext] is (AMPR-388).
     */
    protected suspend fun recallRelevantKnowledgeForTask(task: Task): List<KnowledgeWithScore> {
        // Build context from the task description
        val context = when (task) {
            is Task.CodeChange -> MemoryContext(
                taskType = MemoryTaskTypes.CODE_CHANGE,
                tags = emptySet(),
                description = task.description,
            )
            else -> MemoryContext(
                taskType = MemoryTaskTypes.GENERIC,
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
                    MemoryTaskTypes.CODE_CHANGE
                }
                else -> MemoryTaskTypes.GENERIC
            }

            when (outcome) {
                is Outcome.Success -> tags.add("success")
                is Outcome.Failure -> tags.add("failure")
                else -> tags.add("partial")
            }

            // Store in long-term memory, under the run this agent is animating. `task.id` used
            // to be stamped here, which put a task id in the trace's `run_id` column and left
            // the entry orphaned from its run (AMPR-386).
            storeKnowledge(
                knowledge = knowledge,
                tags = tags,
                taskType = taskType,
                runId = currentRunId,
            )
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
        // AMPR-388: what Recall retrieved reaches the planner. This used to call
        // `runLLMToPlan(task, ideas.toList())`, so every `KnowledgeRecalled` the loop
        // recorded described knowledge no planning prompt ever saw.
        val plan = runLLMToPlan(task, ideas.toList(), relevantKnowledge)
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
     * This is also where a plan's results travel forward (AMPR-408). The walk is
     * sequential and each step is dispatched on its own, so the running list of
     * [StepOutcome]s built here is the only thing that can tell step N what steps
     * 1..N-1 produced; it reaches the tool's parameter prompt through
     * [ExecutionRequest.priorResults]. Without it a plan of the form "search for X,
     * then summarise what you found" generates the summary step's parameters with
     * no idea what the search returned.
     *
     * @param plan The plan containing tasks to execute
     * @return The combined outcome of all tasks in the plan — the first step that
     *   did not succeed, else the last step's outcome, as before.
     */
    override suspend fun executePlan(
        plan: Plan,
    ): Outcome {
        if (plan.tasks.isEmpty()) {
            return Outcome.blank
        }

        val priorResults = mutableListOf<StepOutcome>()
        var runningOutcome: Outcome? = null

        for (task in plan.tasks) {
            rememberNewTask(task)
            val startedAt = Clock.System.now()
            val outcome = executeTaskWithSpark(task, priorResults.toList())
            priorResults += outcome.asPriorResult(task, startedAt)
            runningOutcome = when {
                runningOutcome == null -> outcome
                runningOutcome is Outcome.Success -> outcome
                else -> runningOutcome
            }
        }

        return runningOutcome ?: Outcome.blank
    }

    /**
     * This step's [Outcome], as the [StepOutcome] the next step is told about
     * (AMPR-408).
     *
     * The text comes off the outcome's own payload via
     * [describeResult][link.socket.ampere.agents.execution.describeResult] — the files
     * read, the message, the issues created — because a type name is not a result. A
     * [StepOutcome] already handed up by a step executor is kept as it is: it is the
     * closer record of what the step did.
     *
     * A failure is recorded as non-critical because this walk does not stop at one —
     * it runs every step and reports the first that did not succeed. Marking it
     * critical would claim the plan was cut short when it was not.
     */
    private fun Outcome.asPriorResult(task: Task, startedAt: Instant): StepOutcome {
        val endedAt = (this as? ExecutionOutcome)?.executionEndTimestamp ?: Clock.System.now()
        val description = taskTextFor(task).ifBlank { "Execute step ${task.id}" }
        val detail = (this as? ExecutionOutcome)?.describeResult() ?: ""

        return when (this) {
            is StepOutcome -> this
            // Both blanks: `Outcome.Blank` is what a blank step returns, and
            // `ExecutionOutcome.Blank` is the initialisation value a tool can hand
            // back. Neither is a success, and calling either one would say the step
            // produced something.
            is Outcome.Blank, is ExecutionOutcome.Blank -> StepOutcome.Skipped(
                id = task.id,
                stepDescription = description,
                timestamp = startedAt,
                reason = "nothing to execute",
            )
            is Outcome.Failure -> StepOutcome.Failure(
                id = task.id,
                stepDescription = description,
                startTimestamp = startedAt,
                endTimestamp = endedAt,
                error = detail,
                isCritical = false,
            )
            else -> StepOutcome.Success(
                id = task.id,
                stepDescription = description,
                startTimestamp = startedAt,
                endTimestamp = endedAt,
                details = detail,
            )
        }
    }

    /**
     * Executes a single task with TaskSpark lifecycle management.
     *
     * This internal method applies a TaskSpark before task execution
     * and ensures it's removed afterward.
     *
     * @param task The task to execute
     * @param priorResults What the steps before this one produced (AMPR-408), passed
     *   on to [runLLMToExecuteTask] so the step's tool can be parameterised from them.
     * @return The outcome of the task execution
     */
    private suspend fun executeTaskWithSpark(task: Task, priorResults: List<StepOutcome>): Outcome {
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
            val outcome = runLLMToExecuteTask(task, priorResults)
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
        // A task run on its own has no plan around it, so there is nothing before it.
        return executeTaskWithSpark(task, emptyList())
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

    /**
     * Builds an Idea describing dynamically discovered tools from the ToolRegistry.
     *
     * Returns null if no registry is set or no tools beyond requiredTools are available.
     * This enables agents to perceive MCP tools and other dynamically registered tools
     * during the PERCEIVE phase.
     */
    private suspend fun buildToolAwarenessIdea(): Idea? {
        val registry = toolRegistry ?: return null

        val discoveredTools = registry.getAllTools()
        if (discoveredTools.isEmpty()) return null

        // Only surface tools that aren't already in requiredTools
        val requiredToolIds = requiredTools.map { it.id }.toSet()
        val dynamicTools = discoveredTools.filter { it.id !in requiredToolIds }
        if (dynamicTools.isEmpty()) return null

        val toolSummary = dynamicTools.joinToString("\n") { tool ->
            val source = if (tool.isMcpTool()) " (MCP: ${tool.mcpServerId})" else ""
            "  - ${tool.name}: ${tool.description}$source"
        }

        return Idea(
            name = "Available dynamic tools",
            description = "The following ${dynamicTools.size} tools are dynamically available:\n$toolSummary",
        )
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
            unspark()
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

/**
 * One published version of an agent's state. Deliberately not a data class: identity
 * equality makes every revision distinct, even though [state] is the same mutable instance.
 */
private class StateRevision<S>(val state: S)

/**
 * Read-only [StateFlow] view that emits the agent's state once per [StateRevision].
 *
 * Consecutive emissions may be the same (mutated) state instance, unlike a plain
 * [MutableStateFlow], which would conflate them.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class RevisionedStateFlow<S>(
    private val revisions: StateFlow<StateRevision<S>>,
) : StateFlow<S> {
    override val value: S
        get() = revisions.value.state

    override val replayCache: List<S>
        get() = revisions.replayCache.map { it.state }

    override suspend fun collect(collector: FlowCollector<S>): Nothing =
        revisions.collect { collector.emit(it.state) }
}
