package link.socket.ampere.cli.watch

import link.socket.ampere.cli.watch.presentation.AgentActivityState

/**
 * Maps number keys (1-9) to active agent IDs.
 *
 * This allows users to quickly focus on agents by pressing number keys.
 * The mapping is stable (sorted alphabetically) to avoid confusion as
 * agents come and go.
 */
class AgentIndexMap {
    private val indexToAgentId = mutableMapOf<Int, String>()

    /**
     * Update the mapping based on current agent states.
     *
     * Agents are sorted alphabetically and mapped to indices 1-9.
     * If there are more than 9 agents, only the first 9 are mapped.
     */
    fun update(agentStates: Map<String, AgentActivityState>) {
        indexToAgentId.clear()

        // Sort agents alphabetically by display name for stable ordering
        val sortedAgents = agentStates.values.sortedBy { it.displayName }

        // Map first 9 agents to indices 1-9
        sortedAgents.take(9).forEachIndexed { index, agent ->
            indexToAgentId[index + 1] = agent.agentId
        }
    }

    /**
     * Get the agent ID for a given number key (1-9).
     *
     * @param index The number pressed (1-9)
     * @return The agent ID at that index, or null if no agent is mapped
     */
    fun getAgentId(index: Int): String? {
        return indexToAgentId[index]
    }

    /**
     * Check if an agent ID is currently in the mapping.
     *
     * This is used to detect when a focused agent becomes inactive.
     */
    fun isAgentActive(agentId: String): Boolean {
        return indexToAgentId.values.contains(agentId)
    }

    /**
     * Get the index for a given agent ID.
     *
     * @return The index (1-9) or null if the agent is not mapped
     */
    fun getIndex(agentId: String): Int? {
        return indexToAgentId.entries.find { it.value == agentId }?.key
    }
}
