package link.socket.ampere.cli.coordination

import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.CoordinationStatistics

/**
 * Renders coordination statistics and interaction matrix.
 *
 * This renderer provides aggregate metrics about coordination patterns:
 * - Summary statistics (total interactions, unique pairs, most active)
 * - Interaction matrix showing who coordinates with whom
 * - Session-level metrics for understanding system health
 */
class StatisticsRenderer {

    companion object {
        private const val SECTION_DIVIDER = "────────────────────────────────────────"
    }

    /**
     * Render the statistics view.
     *
     * @param state Current coordination state for matrix calculation
     * @param statistics Aggregate statistics
     * @return Rendered statistics as string
     */
    fun render(state: CoordinationState, statistics: CoordinationStatistics): String {
        val sections = mutableListOf<String>()

        // Summary statistics section
        sections.add(renderSummary(state, statistics))

        // Interaction matrix section
        sections.add("")
        sections.add(renderMatrix(state))

        return sections.joinToString("\n")
    }

    /**
     * Render summary statistics.
     *
     * @param state Current coordination state
     * @param statistics Aggregate statistics
     * @return Rendered summary section
     */
    private fun renderSummary(state: CoordinationState, statistics: CoordinationStatistics): String {
        val lines = mutableListOf<String>()

        lines.add("COORDINATION STATISTICS (session)")
        lines.add(SECTION_DIVIDER)

        // Total interactions
        lines.add("Total interactions:     ${statistics.totalInteractions}")

        // Unique agent pairs
        val allAgents = extractAgentIds(state)
        val possiblePairs = if (allAgents.size > 1) {
            allAgents.size * (allAgents.size - 1)
        } else {
            0
        }
        lines.add("Unique agent pairs:     ${statistics.uniqueAgentPairs}/$possiblePairs possible")

        // Most active agent
        statistics.mostActiveAgent?.let { agent ->
            val shortName = shortenAgentName(agent)
            lines.add("Most active agent:      $shortName")
        }

        // Average interactions per agent
        lines.add("Avg per agent:          ${"%.1f".format(statistics.averageInteractionsPerAgent)}")

        // Meetings held
        val meetingsCount = state.activeMeetings.size
        lines.add("Active meetings:        $meetingsCount")

        // Pending handoffs
        val pendingHandoffsCount = state.pendingHandoffs.size
        lines.add("Pending handoffs:       $pendingHandoffsCount")

        // Human escalations
        val escalationCount = state.recentInteractions.count {
            it.interactionType == link.socket.ampere.coordination.InteractionType.HUMAN_ESCALATION
        }
        lines.add("Human escalations:      $escalationCount")

        return lines.joinToString("\n")
    }

    /**
     * Render interaction matrix.
     *
     * @param state Current coordination state
     * @return Rendered matrix section
     */
    private fun renderMatrix(state: CoordinationState): String {
        val agents = extractAgentIds(state).sorted()

        if (agents.isEmpty()) {
            return "INTERACTION MATRIX\nNo interactions recorded"
        }

        // Build interaction count map
        val interactionCounts = buildInteractionCounts(state)

        val lines = mutableListOf<String>()
        lines.add("INTERACTION MATRIX")

        // Header row with column agent names
        val headerRow = buildString {
            append("".padEnd(15)) // Space for row labels
            agents.forEach { agent ->
                val shortName = shortenAgentName(agent)
                append(shortName.padEnd(12))
            }
        }
        lines.add(headerRow)

        // Data rows
        agents.forEach { sourceAgent ->
            val row = buildString {
                val shortSource = shortenAgentName(sourceAgent)
                append(shortSource.padEnd(15))

                agents.forEach { targetAgent ->
                    val cell = if (sourceAgent == targetAgent) {
                        "-".padEnd(12)
                    } else {
                        val count = interactionCounts[Pair(sourceAgent, targetAgent)] ?: 0
                        count.toString().padEnd(12)
                    }
                    append(cell)
                }
            }
            lines.add(row)
        }

        return lines.joinToString("\n")
    }

    /**
     * Build a map of interaction counts between agents.
     *
     * @param state Current coordination state
     * @return Map of (source, target) -> count
     */
    private fun buildInteractionCounts(state: CoordinationState): Map<Pair<String, String>, Int> {
        val counts = mutableMapOf<Pair<String, String>, Int>()

        // Count from edges
        state.edges.forEach { edge ->
            val key = Pair(edge.sourceAgentId, edge.targetAgentId)
            counts[key] = edge.interactionCount
        }

        // Add counts from recent interactions for pairs not in edges
        state.recentInteractions.forEach { interaction ->
            val target = interaction.targetAgentId ?: "human"
            val key = Pair(interaction.sourceAgentId, target)

            // Only add if not already counted from edges
            if (!counts.containsKey(key)) {
                counts[key] = counts.getOrDefault(key, 0) + 1
            }
        }

        return counts
    }

    /**
     * Extract all unique agent IDs from the coordination state.
     *
     * @param state Current coordination state
     * @return Set of unique agent IDs
     */
    private fun extractAgentIds(state: CoordinationState): Set<String> {
        val agentIds = mutableSetOf<String>()

        // From edges
        state.edges.forEach { edge ->
            agentIds.add(edge.sourceAgentId)
            agentIds.add(edge.targetAgentId)
        }

        // From recent interactions
        state.recentInteractions.forEach { interaction ->
            agentIds.add(interaction.sourceAgentId)
            interaction.targetAgentId?.let { agentIds.add(it) }

            // Check for human interactions
            if (interaction.targetAgentId == null ||
                interaction.interactionType == link.socket.ampere.coordination.InteractionType.HUMAN_ESCALATION ||
                interaction.interactionType == link.socket.ampere.coordination.InteractionType.HUMAN_RESPONSE
            ) {
                agentIds.add("human")
            }
        }

        // From meetings
        state.activeMeetings.forEach { meeting ->
            agentIds.addAll(meeting.participants)
        }

        // From handoffs
        state.pendingHandoffs.forEach { handoff ->
            agentIds.add(handoff.fromAgentId)
            agentIds.add(handoff.toAgentId)
        }

        // From blocked agents
        state.blockedAgents.forEach { blocked ->
            agentIds.add(blocked.agentId)
            agentIds.add(blocked.blockedBy)
        }

        return agentIds
    }

    /**
     * Shorten agent name by removing "Agent" suffix.
     *
     * @param name Agent name to shorten
     * @return Shortened name
     */
    private fun shortenAgentName(name: String): String {
        return name
            .removeSuffix("Agent")
            .removeSuffix("agent")
    }
}
