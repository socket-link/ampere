package link.socket.ampere.domain.arc

import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.Agent
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.propel.GoalCompletion
import link.socket.ampere.propel.TickPhases
import link.socket.ampere.propel.runAgentTick

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

/**
 * @param onGoalCompleted Called once per goal, the first time Flow marks it complete, from the
 *   tick that did. `AmpereRuntime` hands the Goal branch's status write in here (AMPR-419), so
 *   the persisted goal moves as the run does rather than when it settles. It must not throw: a
 *   tick that throws ends the run as ERROR.
 */
class FlowPhase(
    private val arcConfig: ArcConfig,
    private val agents: List<Agent<*>>,
    private val goalTree: GoalTree,
    private val maxTicks: Int = 100,
    private val clock: Clock = Clock.System,
    private val onGoalCompleted: (suspend (GoalNode) -> Unit)? = null,
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

    /**
     * What decides that an outcome completes the current goal: the deterministic default a
     * hosted run asks too (AMPR-442). Not a constructor parameter, because no judgment is
     * wired yet (AMPR-381 J9).
     */
    private val goalCompletion: GoalCompletion = GoalCompletion.AnySuccess

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
            // The tick enters each phase through the agent's own phase manager (AMPR-441),
            // which only an `AutonomousAgent` has. Every agent Charge spawns is one.
            require(agents.all { it is AutonomousAgent<*> }) {
                "FlowPhase brackets each agent's phases through its own PhaseSparkManager, " +
                    "which only an AutonomousAgent has; not one: " +
                    agents.filterNot { it is AutonomousAgent<*> }.joinToString { it.id }
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
        // Use a helper function to work around star projection. The cast holds because
        // `execute` refused any agent that is not an `AutonomousAgent` before the first tick.
        executeAgentTickTyped(agent as AutonomousAgent<*>)
    }

    /**
     * One agent's turn: the tick itself is [runAgentTick]'s, in `propel/` (AMPR-442, H19).
     *
     * What stays here is what makes it the Arc's tick. The task is derived from the Arc's
     * current goal ([taskForCurrentGoal], AMPR-395). The three calls that block are made
     * here: the agent's `perceiveState`, `determinePlanForTask` and `executePlan` each
     * reach a phase service through a `runLLMTo*` lambda wrapped in `runBlockingCompat`,
     * which `propel/` must not call. What the outcome means for the goal tree is decided
     * here as well.
     */
    private suspend fun <S : AgentState> executeAgentTickTyped(agent: AutonomousAgent<S>) {
        val outcome = runAgentTick(
            agent = agent,
            task = taskForCurrentGoal(),
            phases = object : TickPhases {
                override suspend fun perceive(): List<Idea> {
                    val currentState = agent.getCurrentState()
                    val previousIdea = currentState.getCurrentMemory().idea
                    return agent.perceiveState(
                        currentState = currentState,
                        previousIdea,
                    ).ideas
                }

                override suspend fun plan(
                    task: Task,
                    ideas: List<Idea>,
                    relevantKnowledge: List<KnowledgeWithScore>,
                ): Plan = agent.determinePlanForTask(
                    task = task,
                    relevantKnowledge = relevantKnowledge,
                    ideas = ideas.toTypedArray(),
                )

                override suspend fun execute(plan: Plan): Outcome = agent.executePlan(plan)
            },
        ) ?: return

        // Record outcome in shared context
        sharedContext.recordOutcome(agent.id, outcome)

        // Check if this outcome completes the current goal
        evaluateGoalCompletion(outcome)

        // Sync happens after this method via syncAtBarrier().
    }

    /**
     * The task this tick hands to each agent: the Arc's current goal node (AMPR-395).
     *
     * [Task.Blank] for a node with no description, which [runAgentTick] treats as "end the
     * tick". A goal already marked complete is deliberately *not* blank: every agent in a tick
     * gets a turn at the current goal, and completion is evaluated once the tick is over.
     *
     * Derived fresh every tick rather than assigned once in Charge, because the tick is
     * canonical and [evaluateGoalCompletion] moves [SharedContext.currentGoal] on as goals
     * complete. Reading the task back out of the agent's own memory cell instead only ever saw
     * `Task.Blank`: nothing on the Arc path writes that cell, so the goal the person typed
     * never reached an agent.
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

    /**
     * Asks [goalCompletion] whether [outcome] completes the current goal. A yes marks the
     * goal complete and moves on to the next incomplete one.
     *
     * The question is the one a roster-hosted run asks after each executed cycle, and it
     * is answered by the same function (AMPR-442, H19). Its default is the rule this
     * method used to inline, "complete on any success". AMPR-381 J9 replaces that default
     * with a measured judgment.
     */
    private suspend fun evaluateGoalCompletion(outcome: Outcome) {
        if (!goalCompletion.isComplete(outcome)) {
            return
        }

        val goal = sharedContext.currentGoal
        val newlyCompleted = goal !in sharedContext.completedGoals
        sharedContext.markGoalComplete(goal)
        if (newlyCompleted) {
            onGoalCompleted?.invoke(goal)
        }

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
