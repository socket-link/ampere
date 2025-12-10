package link.socket.ampere.agents.definition

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Contract for autonomous agents.
 */
@Serializable
abstract class AutonomousAgent <S : AgentState> : Agent<S>, NeuralAgent<S> {

    // ==================== Agent Metadata ====================

    /** Unique identifier for this agent */
    abstract override val id: AgentId

    /** Set of tools that this agent requires to execute its actions */
    open val requiredTools: Set<Tool<*>> = emptySet()

    // ==================== Agent State ====================

    private var agentIsRunning = false
    private var agentRuntimeScope: CoroutineScope? = null
    private var agentRuntimeLoopJob: Job? = null

    // ==================== Agent Runtime ====================

    protected suspend fun runtimeLoop() {
        while (agentIsRunning) {
            val currentTask = getCurrentState().getCurrentMemory().task

            val previousIdea = getCurrentState().getCurrentMemory().idea

            val statePerception = perceiveState(
                currentState = getCurrentState(),
                newIdeas = listOf(previousIdea).toTypedArray(),
            )
            rememberNewPerception(statePerception)

            // Recall relevant knowledge from past similar tasks
            val relevantKnowledge = recallRelevantKnowledgeForTask(currentTask)

            relevantKnowledge.map { knowledge ->
                knowledge.knowledge.learnings
            }

            val plan = determinePlanForTask(
                task = currentTask,
                relevantKnowledge = relevantKnowledge,
                ideas = statePerception.ideas.toTypedArray(),
            )
            rememberNewPlan(plan)

            val outcome = executePlan(plan)
            rememberNewOutcome(outcome)

            // Extract and store knowledge from this execution
            extractAndStoreKnowledge(outcome, currentTask, plan)

            val nextIdea = evaluateNextIdeaFromOutcomes(outcome)
            rememberNewIdea(nextIdea)

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
                description = task.description
            )
            else -> MemoryContext(
                taskType = "generic",
                tags = emptySet(),
                description = "Generic task: ${task.id}"
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
        relevantKnowledge: List<KnowledgeWithScore>
    ): Plan {
        // Note: AutonomousAgent implementations should override this to incorporate
        // relevantKnowledge into their planning. The base implementation here
        // ignores knowledge for backwards compatibility.
        val plan = runLLMToPlan(task, ideas.toList())
        rememberNewPlan(plan)
        return plan
    }

    /** Executes a plan of actions */
    override suspend fun executePlan(
        plan: Plan,
    ): Outcome {
        if (plan.tasks.isEmpty()) {
            return Outcome.blank
        }

        return plan.tasks.map { task ->
            rememberNewTask(task)
            val outcome = runLLMToExecuteTask(task)
            rememberNewOutcome(outcome)
            outcome
        }.reduce { runningOutcome, outcome ->
            if (runningOutcome !is Outcome.Success) {
                runningOutcome
            } else {
                outcome
            }
        }
    }

    /** Executes a task from a plan */
    override suspend fun runTask(task: Task): Outcome {
        val outcome = runLLMToExecuteTask(task)
        rememberNewOutcome(outcome)
        return outcome
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
}
