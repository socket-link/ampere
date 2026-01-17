package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant

/**
 * Represents a Spark transition event for display purposes.
 */
data class SparkTransition(
    val timestamp: Instant,
    val sparkName: String,
    val sparkType: String,
    val direction: SparkTransitionDirection,
    val resultingDepth: Int,
    val agentId: String
)

/**
 * Direction of a Spark transition.
 */
enum class SparkTransitionDirection {
    /** A Spark was pushed onto the stack - context specialization. */
    APPLIED,
    /** A Spark was popped from the stack - context generalization. */
    REMOVED
}

/**
 * Complete cognitive state snapshot for display.
 */
data class CognitiveDisplayState(
    val affinityName: String,
    val sparkNames: List<String>,
    val depth: Int,
    val effectivePromptLength: Int?,
    val availableToolCount: Int?,
    val description: String
)

/**
 * Collects and maintains Spark transition history per agent.
 */
class SparkHistoryCollector {
    private val historyByAgent = mutableMapOf<String, MutableList<SparkTransition>>()
    private val cognitiveStateByAgent = mutableMapOf<String, CognitiveDisplayState>()

    /** Maximum history entries to keep per agent. */
    private val maxHistoryPerAgent = 50

    /**
     * Record a Spark applied event.
     */
    fun recordApplied(
        agentId: String,
        timestamp: Instant,
        sparkName: String,
        sparkType: String,
        stackDepth: Int,
        stackDescription: String
    ) {
        val transition = SparkTransition(
            timestamp = timestamp,
            sparkName = sparkName,
            sparkType = sparkType,
            direction = SparkTransitionDirection.APPLIED,
            resultingDepth = stackDepth,
            agentId = agentId
        )
        addTransition(agentId, transition)

        // Update cognitive state - extract spark names from description
        updateCognitiveStateFromDescription(agentId, stackDescription, stackDepth)
    }

    /**
     * Record a Spark removed event.
     */
    fun recordRemoved(
        agentId: String,
        timestamp: Instant,
        previousSparkName: String,
        stackDepth: Int,
        stackDescription: String
    ) {
        val transition = SparkTransition(
            timestamp = timestamp,
            sparkName = previousSparkName,
            sparkType = "unknown", // Not available in remove event
            direction = SparkTransitionDirection.REMOVED,
            resultingDepth = stackDepth,
            agentId = agentId
        )
        addTransition(agentId, transition)

        // Update cognitive state
        updateCognitiveStateFromDescription(agentId, stackDescription, stackDepth)
    }

    /**
     * Record a cognitive state snapshot.
     */
    fun recordSnapshot(
        agentId: String,
        affinity: String,
        sparkNames: List<String>,
        stackDepth: Int,
        effectivePromptLength: Int,
        availableToolCount: Int?,
        stackDescription: String
    ) {
        cognitiveStateByAgent[agentId] = CognitiveDisplayState(
            affinityName = affinity,
            sparkNames = sparkNames,
            depth = stackDepth,
            effectivePromptLength = effectivePromptLength,
            availableToolCount = availableToolCount,
            description = stackDescription
        )
    }

    /**
     * Get recent transition history for an agent.
     */
    fun getHistory(agentId: String, limit: Int = 20): List<SparkTransition> {
        return historyByAgent[agentId]?.takeLast(limit) ?: emptyList()
    }

    /**
     * Get all recent transitions across all agents.
     */
    fun getAllRecentTransitions(limit: Int = 50): List<SparkTransition> {
        return historyByAgent.values
            .flatten()
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /**
     * Get the current cognitive state for an agent.
     */
    fun getCognitiveState(agentId: String): CognitiveDisplayState? {
        return cognitiveStateByAgent[agentId]
    }

    /**
     * Get spark names for an agent from the most recent state.
     */
    fun getSparkNames(agentId: String): List<String> {
        return cognitiveStateByAgent[agentId]?.sparkNames ?: emptyList()
    }

    /**
     * Get affinity name for an agent.
     */
    fun getAffinityName(agentId: String): String? {
        return cognitiveStateByAgent[agentId]?.affinityName
    }

    /**
     * Get spark depth for an agent.
     */
    fun getSparkDepth(agentId: String): Int {
        return cognitiveStateByAgent[agentId]?.depth ?: 0
    }

    /**
     * Clear history for an agent (when agent becomes inactive).
     */
    fun clearAgent(agentId: String) {
        historyByAgent.remove(agentId)
        cognitiveStateByAgent.remove(agentId)
    }

    private fun addTransition(agentId: String, transition: SparkTransition) {
        val history = historyByAgent.getOrPut(agentId) { mutableListOf() }
        history.add(transition)

        // Trim to max size
        while (history.size > maxHistoryPerAgent) {
            history.removeAt(0)
        }
    }

    private fun updateCognitiveStateFromDescription(agentId: String, description: String, depth: Int) {
        // Description format: [AFFINITY] → [Spark1] → [Spark2] → ...
        val parts = description.split(" → ")
        if (parts.isEmpty()) return

        val affinityName = parts[0].removeSurrounding("[", "]")
        val sparkNames = parts.drop(1).map { it.removeSurrounding("[", "]") }

        // Update or create the cognitive state
        val existing = cognitiveStateByAgent[agentId]
        cognitiveStateByAgent[agentId] = CognitiveDisplayState(
            affinityName = affinityName,
            sparkNames = sparkNames,
            depth = depth,
            effectivePromptLength = existing?.effectivePromptLength,
            availableToolCount = existing?.availableToolCount,
            description = description
        )
    }
}
