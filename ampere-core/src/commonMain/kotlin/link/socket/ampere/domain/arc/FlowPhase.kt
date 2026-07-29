package link.socket.ampere.domain.arc

import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.agents.definition.Agent
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task

data class FlowResult(
    val completedGoals: List<GoalNode>,
    val finalTick: Int,
    val agentOutcomes: Map<String, List<Outcome>>,
    val terminationReason: TerminationReason,
)

enum class TerminationReason {
    /** Every goal in the tree was completed. */
    GOAL_COMPLETE,

    /** The tick budget ran out before the goal tree was complete. */
    MAX_TICKS_REACHED,

    /** A caller asked for a graceful stop via [FlowPhase.stop] / `AmpereRuntime.stop()`. */
    MANUAL_STOP,

    /** The Flow coroutine was cancelled; the tick loop bailed at its next cancellation point. */
    CANCELLED,

    /** A tick threw. The exception is rethrown; this reason only labels the partial result. */
    ERROR,
}

data class SharedContext(
    val goalTree: GoalTree,
    var currentGoal: GoalNode,
    val completedGoals: MutableList<GoalNode> = mutableListOf(),
    val agentOutcomes: MutableMap<String, MutableList<Outcome>> = mutableMapOf(),
) {
    fun recordOutcome(agentId: String, outcome: Outcome) {
        agentOutcomes.getOrPut(agentId) { mutableListOf() }.add(outcome)
    }

    fun markGoalComplete(goal: GoalNode) {
        completedGoals.add(goal)
    }

    fun isGoalTreeComplete(): Boolean {
        val allGoals = goalTree.allNodes()
        return completedGoals.containsAll(allGoals)
    }
}

class FlowPhase(
    private val arcConfig: ArcConfig,
    private val agents: List<Agent<*>>,
    private val goalTree: GoalTree,
    private val maxTicks: Int = 100,
) {
    // Volatile because [stop] is called from another thread while the tick loop is running, and
    // [getCurrentTick]/[snapshot] are read from another thread while it is still ticking.
    @Volatile
    private var currentTick = 0

    private val sharedContext = SharedContext(
        goalTree = goalTree,
        currentGoal = goalTree.root,
    )
    private val barrierMutex = Mutex()

    @Volatile
    private var isComplete = false

    @Volatile
    private var terminationReason: TerminationReason? = null

    suspend fun execute(): FlowResult {
        require(agents.isNotEmpty()) { "FlowPhase requires at least one agent" }

        val orchestrationType = arcConfig.orchestration.type
        require(orchestrationType == OrchestrationType.SEQUENTIAL) {
            "FlowPhase currently only supports SEQUENTIAL orchestration"
        }

        try {
            while (!isComplete && currentTick < maxTicks) {
                // The tick loop is the Arc's cancellation point: a tick can be long and an
                // inner suspend call may never hit one, so check explicitly every tick.
                coroutineContext.ensureActive()

                executeTick()
                currentTick++

                if (sharedContext.isGoalTreeComplete()) {
                    isComplete = true
                    terminationReason = TerminationReason.GOAL_COMPLETE
                }
            }
        } catch (e: CancellationException) {
            terminationReason = TerminationReason.CANCELLED
            throw e
        } catch (e: Throwable) {
            terminationReason = TerminationReason.ERROR
            throw e
        }

        if (!isComplete && currentTick >= maxTicks) {
            terminationReason = TerminationReason.MAX_TICKS_REACHED
        }

        return snapshot()
    }

    /**
     * The Flow's progress so far, as a [FlowResult].
     *
     * [execute] returns this on the happy path, and callers that hold the [FlowPhase] can call
     * it after a cancellation or failure to recover the partial run — that is the whole reason
     * `AmpereRuntime` holds the instance rather than dropping it.
     */
    fun snapshot(): FlowResult = FlowResult(
        completedGoals = sharedContext.completedGoals.toList(),
        finalTick = currentTick,
        agentOutcomes = sharedContext.agentOutcomes.mapValues { it.value.toList() },
        terminationReason = terminationReason ?: TerminationReason.MANUAL_STOP,
    )

    private suspend fun executeTick() {
        val agentOrder = determineAgentOrder()

        for (agent in agentOrder) {
            executeAgentTick(agent)
            syncAtBarrier()
        }
    }

    private fun determineAgentOrder(): List<Agent<*>> {
        val order = arcConfig.orchestration.order
        if (order.isEmpty()) {
            return agents
        }

        val agentsByRole = arcConfig.agents.mapIndexed { index, config ->
            config.role to agents.getOrNull(index)
        }.toMap()

        return order.mapNotNull { role -> agentsByRole[role] }
    }

    private suspend fun executeAgentTick(agent: Agent<*>) {
        // Use a helper function to work around star projection
        executeAgentTickTyped(agent)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <S : AgentState> executeAgentTickTyped(agent: Agent<S>) {
        // 1. Perceive - observe current state
        val currentState = agent.getCurrentState()
        val previousIdea = currentState.getCurrentMemory().idea

        val perception = agent.perceiveState(
            currentState = currentState,
            previousIdea,
        )

        // 2. Remember - recall relevant knowledge
        val currentTask = currentState.getCurrentMemory().task
        val relevantKnowledge = recallKnowledge(agent, currentTask)

        // 3. Optimize - determine best plan (determinePlanForTask does this)
        // 4. Plan - construct execution plan
        val plan = agent.determinePlanForTask(
            task = currentTask,
            relevantKnowledge = relevantKnowledge,
            ideas = perception.ideas.toTypedArray(),
        )

        // 5. Execute - run the plan
        val outcome = agent.executePlan(plan)

        // Record outcome in shared context
        sharedContext.recordOutcome(agent.id, outcome)

        // Check if this outcome completes the current goal
        if (outcome is Outcome.Success) {
            evaluateGoalCompletion()
        }

        // 6. Sync happens after this method via syncAtBarrier()
    }

    private suspend fun recallKnowledge(agent: Agent<*>, task: Task): List<KnowledgeWithScore> {
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

        return agent.recallRelevantKnowledge(context, limit = 10)
            .getOrElse { emptyList() }
    }

    private fun evaluateGoalCompletion() {
        // Simple heuristic: mark current goal as complete on any success
        // In a real implementation, this would use more sophisticated criteria
        sharedContext.markGoalComplete(sharedContext.currentGoal)

        // Move to next incomplete goal if available
        val nextGoal = goalTree.allNodes()
            .firstOrNull { !sharedContext.completedGoals.contains(it) }

        if (nextGoal != null) {
            sharedContext.currentGoal = nextGoal
        }
    }

    private suspend fun syncAtBarrier() {
        barrierMutex.withLock {
            // All agents wait here until everyone completes their tick
            // In sequential mode, this is a no-op since agents run one at a time
            // But it's here for future parallel support
        }
    }

    fun getCurrentTick(): Int = currentTick

    fun isComplete(): Boolean = isComplete

    /**
     * Request a graceful stop. The tick loop exits after the tick in flight, and the run
     * terminates with [TerminationReason.MANUAL_STOP].
     */
    fun stop() {
        isComplete = true
        terminationReason = TerminationReason.MANUAL_STOP
    }
}
