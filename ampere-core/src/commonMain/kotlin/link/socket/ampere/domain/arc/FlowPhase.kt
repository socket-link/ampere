package link.socket.ampere.domain.arc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.agents.definition.Agent
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task

data class FlowResult(
    val completedGoals: List<GoalNode>,
    val finalTick: Int,
    val agentOutcomes: Map<String, List<Outcome>>,
    val terminationReason: TerminationReason,
)

enum class TerminationReason {
    GOAL_COMPLETE,
    MAX_TICKS_REACHED,
    MANUAL_STOP,
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
    private var currentTick = 0
    private val sharedContext = SharedContext(
        goalTree = goalTree,
        currentGoal = goalTree.root,
    )
    private val barrierMutex = Mutex()
    private var isComplete = false
    private var terminationReason: TerminationReason? = null

    suspend fun execute(): FlowResult {
        require(agents.isNotEmpty()) { "FlowPhase requires at least one agent" }

        val orchestrationType = arcConfig.orchestration.type
        require(orchestrationType == OrchestrationType.SEQUENTIAL) {
            "FlowPhase currently only supports SEQUENTIAL orchestration"
        }

        while (!isComplete && currentTick < maxTicks) {
            executeTick()
            currentTick++

            if (sharedContext.isGoalTreeComplete()) {
                isComplete = true
                terminationReason = TerminationReason.GOAL_COMPLETE
            }
        }

        if (!isComplete && currentTick >= maxTicks) {
            terminationReason = TerminationReason.MAX_TICKS_REACHED
        }

        return FlowResult(
            completedGoals = sharedContext.completedGoals.toList(),
            finalTick = currentTick,
            agentOutcomes = sharedContext.agentOutcomes.mapValues { it.value.toList() },
            terminationReason = terminationReason ?: TerminationReason.MANUAL_STOP,
        )
    }

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

    fun stop() {
        isComplete = true
        terminationReason = TerminationReason.MANUAL_STOP
    }
}
