package link.socket.ampere.agents.core

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.memory.MemoryContext
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Contract for autonomous agents.
 */
@Serializable
abstract class AutonomousAgent <S : AgentState> : Agent<S>() {

    // ==================== Agent Metadata ====================

    /** Unique identifier for this agent */
    abstract override val id: AgentId

    /** Set of tools that this agent requires to execute its actions */
    open val requiredTools: Set<Tool<*>> = emptySet()

    abstract val runLLMToEvaluatePerception: (perception: Perception<S>) -> Idea
    abstract val runLLMToPlan: (task: Task, ideas: List<Idea>, relevantKnowledge: List<Knowledge>) -> Plan
    abstract val runLLMToExecuteTask: (task: Task) -> Outcome
    abstract val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome
    abstract val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea

    // ==================== Agent State ====================

    private var agentIsRunning = false
    private var agentRuntimeScope: CoroutineScope? = null
    private var agentRuntimeLoopJob: Job? = null

    // ==================== Agent Runtime ====================

    protected suspend fun runtimeLoop() {
        while (agentIsRunning) {
            val currentTask = getCurrentState().getCurrentMemory().task

            val previousIdea = getCurrentState().getCurrentMemory().idea

            val idea = perceiveState(previousIdea)
            rememberNewIdea(idea)

            // Recall relevant knowledge before planning
            val relevantKnowledge = recallKnowledgeForTask(currentTask).getOrElse { emptyList() }

            val plan = determinePlanForTask(currentTask, idea, relevantKnowledge)
            rememberNewPlan(plan)

            val outcome = executePlan(plan)
            rememberNewOutcome(outcome)

            val nextIdea = evaluateNextIdeaFromOutcomes(outcome)
            rememberNewIdea(nextIdea)

            delay(1.seconds)
        }
    }

    /**
     * Recall relevant knowledge for a given task.
     * Builds a MemoryContext from the task and queries stored learnings.
     */
    private suspend fun recallKnowledgeForTask(task: Task): Result<List<KnowledgeWithScore>> {
        // Build context from task
        val context = MemoryContext(
            taskType = task.id, // Use task ID as type for now
            tags = emptySet(), // Could extract tags from task description in the future
            description = task.description
        )

        return recallRelevantKnowledge(context, limit = 10)
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
        vararg newIdeas: Idea,
    ): Idea {
        val perception = Perception(
            id = generateUUID(id),
            ideas = newIdeas.toList(),
            currentState = getCurrentState(),
            timestamp = Clock.System.now(),
        )
        rememberNewPerception(perception)

        val idea = runLLMToEvaluatePerception(perception)
        return idea
    }

    /** Breaks down a complex task into smaller tasks */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>
    ): Plan {
        // Extract Knowledge objects from KnowledgeWithScore wrappers
        val knowledgeList = relevantKnowledge.map { it.knowledge }

        val plan = runLLMToPlan(task, ideas.toList(), knowledgeList)
        rememberNewPlan(plan)
        return plan
    }

    /** Executes a plan of actions */
    override suspend fun executePlan(
        plan: Plan,
    ): Outcome =
        plan.tasks.map { task ->
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
