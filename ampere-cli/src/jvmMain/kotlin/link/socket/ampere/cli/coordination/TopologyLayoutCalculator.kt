package link.socket.ampere.cli.coordination

import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.InteractionType
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Calculates grid layout positions for topology graph visualization.
 *
 * This calculator arranges agent nodes in an ASCII-friendly grid, ranks them by
 * connectivity, and detects bidirectional relationships between agents.
 */
class TopologyLayoutCalculator {

    companion object {
        private const val NODE_WIDTH = 15
        private const val NODE_HEIGHT = 3
        private const val HORIZONTAL_SPACING = 8
        private const val VERTICAL_SPACING = 4
    }

    /**
     * Calculate a topology layout from coordination state and agent states.
     *
     * @param state Current coordination state
     * @param agentStates Map of agent ID to their current operational state
     * @return Complete topology layout ready for rendering
     */
    fun calculateLayout(
        state: CoordinationState,
        agentStates: Map<String, NodeState>,
    ): TopologyLayout {
        // Extract unique agents from edges and interactions
        val agentIds = extractAgentIds(state)

        // Rank agents by interaction count (most connected = more central)
        val rankedAgents = rankAgentsByConnectivity(agentIds, state)

        // Separate human from agents
        val (humanAgents, regularAgents) = rankedAgents.partition { it == "human" }

        // Calculate grid dimensions
        val gridDimensions = calculateGridDimensions(regularAgents.size)

        // Position regular agents in grid
        val regularNodes = positionAgentsInGrid(regularAgents, gridDimensions, agentStates)

        // Position human at bottom if present
        val allNodes = if (humanAgents.isNotEmpty()) {
            val humanNode = positionHumanNode(gridDimensions, agentStates["human"] ?: NodeState.IDLE)
            regularNodes + humanNode
        } else {
            regularNodes
        }

        // Build edges with bidirectional detection
        val edges = buildEdges(state, allNodes)

        // Calculate total layout dimensions
        val width = gridDimensions.columns * (NODE_WIDTH + HORIZONTAL_SPACING)
        val height = if (humanAgents.isNotEmpty()) {
            (gridDimensions.rows + 1) * (NODE_HEIGHT + VERTICAL_SPACING) + VERTICAL_SPACING
        } else {
            gridDimensions.rows * (NODE_HEIGHT + VERTICAL_SPACING)
        }

        return TopologyLayout(
            nodes = allNodes,
            edges = edges,
            width = width,
            height = height,
        )
    }

    /**
     * Extract all unique agent IDs from the coordination state.
     */
    private fun extractAgentIds(state: CoordinationState): Set<String> {
        val agentIds = mutableSetOf<String>()

        // Add agents from edges
        state.edges.forEach { edge ->
            agentIds.add(edge.sourceAgentId)
            agentIds.add(edge.targetAgentId)
        }

        // Add agents from recent interactions
        state.recentInteractions.forEach { interaction ->
            agentIds.add(interaction.sourceAgentId)
            interaction.targetAgentId?.let { agentIds.add(it) }

            // Add "human" for human escalations or responses
            if (interaction.interactionType == InteractionType.HUMAN_ESCALATION ||
                interaction.interactionType == InteractionType.HUMAN_RESPONSE
            ) {
                agentIds.add("human")
            }
        }

        // Add agents from blocked list
        state.blockedAgents.forEach { blocked ->
            agentIds.add(blocked.agentId)
        }

        // Add agents from pending handoffs
        state.pendingHandoffs.forEach { handoff ->
            agentIds.add(handoff.fromAgentId)
            agentIds.add(handoff.toAgentId)
        }

        // Add agents from active meetings
        state.activeMeetings.forEach { meeting ->
            meeting.participants.forEach { agentIds.add(it) }
        }

        return agentIds
    }

    /**
     * Rank agents by their interaction count (most connected first).
     */
    private fun rankAgentsByConnectivity(
        agentIds: Set<String>,
        state: CoordinationState,
    ): List<String> {
        val connectivityMap = agentIds.associateWith { agentId ->
            state.edges.count { edge ->
                edge.sourceAgentId == agentId || edge.targetAgentId == agentId
            }
        }

        return agentIds.sortedByDescending { connectivityMap[it] ?: 0 }
    }

    /**
     * Calculate grid dimensions that roughly form a square layout.
     */
    private fun calculateGridDimensions(agentCount: Int): GridDimensions {
        if (agentCount == 0) {
            return GridDimensions(rows = 0, columns = 0)
        }

        val columns = ceil(sqrt(agentCount.toDouble())).toInt()
        val rows = ceil(agentCount.toDouble() / columns).toInt()

        return GridDimensions(rows = rows, columns = columns)
    }

    /**
     * Position regular agents in a grid layout.
     */
    private fun positionAgentsInGrid(
        agents: List<String>,
        gridDimensions: GridDimensions,
        agentStates: Map<String, NodeState>,
    ): List<TopologyNode> {
        return agents.mapIndexed { index, agentId ->
            val row = index / gridDimensions.columns
            val col = index % gridDimensions.columns

            val x = col * (NODE_WIDTH + HORIZONTAL_SPACING)
            val y = row * (NODE_HEIGHT + VERTICAL_SPACING)

            TopologyNode(
                agentId = agentId,
                displayName = shortenAgentName(agentId),
                state = agentStates[agentId] ?: NodeState.IDLE,
                position = NodePosition(x, y),
                isHuman = false,
            )
        }
    }

    /**
     * Position the human node at the bottom of the layout.
     */
    private fun positionHumanNode(
        gridDimensions: GridDimensions,
        state: NodeState,
    ): TopologyNode {
        val y = (gridDimensions.rows + 1) * (NODE_HEIGHT + VERTICAL_SPACING)
        val x = (gridDimensions.columns / 2) * (NODE_WIDTH + HORIZONTAL_SPACING)

        return TopologyNode(
            agentId = "human",
            displayName = "Human",
            state = state,
            position = NodePosition(x, y),
            isHuman = true,
        )
    }

    /**
     * Shorten agent names for display by removing common suffixes.
     */
    private fun shortenAgentName(agentId: String): String {
        return agentId
            .removeSuffix("Agent")
            .removeSuffix("agent")
            .take(NODE_WIDTH - 2) // Leave room for borders
    }

    /**
     * Build edges from coordination state with bidirectional detection.
     */
    private fun buildEdges(
        state: CoordinationState,
        nodes: List<TopologyNode>,
    ): List<TopologyEdge> {
        val nodeIds = nodes.map { it.agentId }.toSet()
        val edges = mutableListOf<TopologyEdge>()
        val processedPairs = mutableSetOf<Pair<String, String>>()

        for (coordinationEdge in state.edges) {
            val source = coordinationEdge.sourceAgentId
            val target = coordinationEdge.targetAgentId

            // Skip edges to nodes that don't exist in our layout
            if (source !in nodeIds || target !in nodeIds) {
                continue
            }

            // Check if we've already processed this pair
            val pair = minOf(source, target) to maxOf(source, target)
            if (pair in processedPairs) {
                continue
            }
            processedPairs.add(pair)

            // Check if there's a reverse edge
            val reverseEdge = state.edges.find {
                it.sourceAgentId == target && it.targetAgentId == source
            }

            val isBidirectional = reverseEdge != null

            // Combine interaction types from both directions if bidirectional
            val interactionTypes = if (isBidirectional) {
                coordinationEdge.interactionTypes + (reverseEdge?.interactionTypes ?: emptySet())
            } else {
                coordinationEdge.interactionTypes
            }

            val label = summarizeInteractionTypes(interactionTypes)

            edges.add(
                TopologyEdge(
                    source = source,
                    target = target,
                    label = label,
                    isActive = true,
                    isBidirectional = isBidirectional,
                    interactionTypes = interactionTypes,
                ),
            )
        }

        return edges
    }

    /**
     * Create a short label summarizing interaction types.
     */
    private fun summarizeInteractionTypes(types: Set<InteractionType>): String {
        if (types.isEmpty()) return ""

        return when {
            types.size == 1 -> abbreviateInteractionType(types.first())
            types.contains(InteractionType.TICKET_ASSIGNED) -> "TASK"
            types.contains(InteractionType.REVIEW_REQUEST) -> "REV"
            types.contains(InteractionType.DELEGATION) -> "DEL"
            types.contains(InteractionType.HELP_REQUEST) -> "HELP"
            else -> "COLLAB"
        }
    }

    /**
     * Abbreviate interaction type for compact display.
     */
    private fun abbreviateInteractionType(type: InteractionType): String {
        return when (type) {
            InteractionType.TICKET_ASSIGNED -> "TASK"
            InteractionType.CLARIFICATION_REQUEST -> "CLAR"
            InteractionType.CLARIFICATION_RESPONSE -> "RESP"
            InteractionType.REVIEW_REQUEST -> "REV"
            InteractionType.REVIEW_COMPLETE -> "DONE"
            InteractionType.MEETING_INVITE -> "MEET"
            InteractionType.MEETING_MESSAGE -> "MEET"
            InteractionType.HELP_REQUEST -> "HELP"
            InteractionType.HELP_RESPONSE -> "HELP"
            InteractionType.DELEGATION -> "DEL"
            InteractionType.HUMAN_ESCALATION -> "ESC"
            InteractionType.HUMAN_RESPONSE -> "RESP"
        }
    }

    /**
     * Grid dimensions for layout.
     */
    private data class GridDimensions(
        val rows: Int,
        val columns: Int,
    )
}
