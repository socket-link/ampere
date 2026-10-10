package link.socket.ampere.domain.arc

import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.Agent
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
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

/**
 * State shared by every agent across the ticks of one Flow.
 *
 * @param clock The Arc's single clock (AMPR-335). Anything on the tick that needs "now" reads
 *   it here, so an injected clock governs the whole run.
 */
data class SharedContext(
    val goalTree: GoalTree,
    var currentGoal: GoalNode,
    val completedGoals: MutableList<GoalNode> = mutableListOf(),
    val agentOutcomes: MutableMap<String, MutableList<Outcome>> = mutableMapOf(),
    val clock: Clock = Clock.System,
) {
    fun recordOutcome(agentId: String, outcome: Outcome) {
        agentOutcomes.getOrPut(agentId) { mutableListOf() }.add(outcome)
    }

    /**
     * Record [goal] as complete, at most once.
     *
     * Idempotent because every agent in a tick works the same [currentGoal] and
     * [FlowPhase.evaluateGoalCompletion] marks it on each success — so a three-agent tick would
     * otherwise report a one-node tree as three completed goals (AMPR-395). Unreachable before
     * that ticket, since no Arc run ever produced a success to mark one with.
     */
    fun markGoalComplete(goal: GoalNode) {
        if (completedGoals.contains(goal)) {
            return
        }
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
    private val clock: Clock = Clock.System,
) {
    // Volatile because [stop] is called from another thread while the tick loop is running, and
    // [getCurrentTick]/[snapshot] are read from another thread while it is still ticking.
    @Volatile
    private var currentTick = 0

    internal val sharedContext = SharedContext(
        goalTree = goalTree,
        currentGoal = goalTree.root,
        clock = clock,
    )
    private val barrierMutex = Mutex()

    @Volatile
    private var isComplete = false

    @Volatile
    private var terminationReason: TerminationReason? = null

    suspend fun execute(): FlowResult {
        try {
            // Inside the `try` so a rejected configuration is labelled ERROR in the snapshot,
            // not the MANUAL_STOP fallback.
            require(agents.isNotEmpty()) { "FlowPhase requires at least one agent" }
            require(arcConfig.orchestration.type == OrchestrationType.SEQUENTIAL) {
                "FlowPhase currently only supports SEQUENTIAL orchestration"
            }

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
        // 0. Take the tick's task from the Arc's current goal (AMPR-395).
        //
        // The tick is what connects Charge's goal tree to an agent. Reading the task back out
        // of the agent's own memory cell instead only ever saw `Task.Blank`: the sole writers
        // of that cell are the agent's `executePlan`/`runTask`, and nothing on the Arc path
        // calls either — so the goal the person typed never reached an agent, every tick
        // planned `Plan.blank`, and no run could complete a goal.
        //
        // Derived fresh every tick rather than assigned once in Charge, because the tick is
        // canonical and `evaluateGoalCompletion` moves [SharedContext.currentGoal] on as goals
        // complete.
        val currentTask = taskForCurrentGoal()

        // Nothing to work: end the tick here rather than spending a Perceive call on a state
        // no later phase can act on. A blank task plans `Plan.blank`, which executes to
        // `Outcome.blank` — not a success — so such a tick can only burn budget.
        if (currentTask is Task.Blank) {
            return
        }

        // Assign it, so the phases that read the agent's state rather than their arguments see
        // what it is working on: `defaultPerceptionContext` renders the current memory cell's
        // task, and an unassigned cell asks Perceive to reason about "(none assigned yet)".
        agent.rememberNewTask(currentTask)

        val plan = try {
            // 1. Perceive - observe current state
            val currentState = agent.getCurrentState()
            val previousIdea = currentState.getCurrentMemory().idea

            val perception = agent.perceiveState(
                currentState = currentState,
                previousIdea,
            )

            // 2. Remember - recall relevant knowledge
            val relevantKnowledge = recallKnowledge(agent, currentTask)

            // 3. Optimize - determine best plan (determinePlanForTask does this)
            // 4. Plan - construct execution plan
            agent.determinePlanForTask(
                task = currentTask,
                relevantKnowledge = relevantKnowledge,
                ideas = perception.ideas.toTypedArray(),
            )
        } finally {
            // Close the assignment out while the cell still holds it, which is the only moment
            // `finishCurrentTask` can release the `TaskSpark` that `rememberNewTask` pushed:
            // `executePlan` records each plan step as the current task, so by the time it
            // returns the cell names a step and the goal's spark would be stranded in the
            // system prompt for the rest of the run. Execute does not need the assignment in
            // the cell — `executeTaskWithSpark` pushes and pops a spark per step.
            agent.finishCurrentTask()
        }

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

    /**
     * The task this tick hands to each agent: the Arc's current goal node (AMPR-395).
     *
     * [Task.Blank] for a node with no description, which [executeAgentTickTyped] treats as "end
     * the tick". A goal already marked complete is deliberately *not* blank: every agent in a
     * tick gets a turn at the current goal, and completion is evaluated once the tick is over.
     *
     * Handed to the agent two ways, because the phases read it two ways: as the argument
     * `determinePlanForTask` plans from, and through `rememberNewTask` so the Perceive phase's
     * context builder — which reads the memory cell, not its arguments (AMPR-403) — describes
     * the goal rather than "(none assigned yet)".
     */
    private fun taskForCurrentGoal(): Task {
        val goal = sharedContext.currentGoal
        if (goal.description.isBlank()) {
            return Task.Blank
        }

        return Task.CodeChange(
            id = goal.id,
            status = TaskStatus.Pending,
            description = goal.description,
        )
    }

    private suspend fun recallKnowledge(agent: Agent<*>, task: Task): List<KnowledgeWithScore> {
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
