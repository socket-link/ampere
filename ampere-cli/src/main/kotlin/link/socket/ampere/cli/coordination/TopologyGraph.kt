package link.socket.ampere.cli.coordination

import link.socket.ampere.coordination.InteractionType

/**
 * Character cell coordinates for ASCII rendering.
 *
 * @property x Horizontal position (column)
 * @property y Vertical position (row)
 */
data class NodePosition(
    val x: Int,
    val y: Int,
)

/**
 * Represents an agent node in the topology graph.
 *
 * @property agentId Full agent identifier
 * @property displayName Shortened name for display
 * @property state Current operational state of the agent
 * @property position Position in the character grid
 * @property isHuman True if this represents a human, false for agent
 */
data class TopologyNode(
    val agentId: String,
    val displayName: String,
    val state: NodeState,
    val position: NodePosition,
    val isHuman: Boolean = false,
)

/**
 * Operational state of an agent node.
 */
enum class NodeState {
    /** Agent is actively processing */
    ACTIVE,

    /** Agent is idle but available */
    IDLE,

    /** Agent is blocked waiting on another agent or human */
    BLOCKED,

    /** Agent is offline or unavailable */
    OFFLINE,
}

/**
 * Represents a coordination edge between two nodes.
 *
 * @property source Source agent ID
 * @property target Target agent ID
 * @property label Summary of interaction types on this edge
 * @property isActive True if interaction occurred recently (within time window)
 * @property isBidirectional True if both agents interact with each other
 * @property interactionTypes Set of interaction types on this edge
 */
data class TopologyEdge(
    val source: String,
    val target: String,
    val label: String,
    val isActive: Boolean,
    val isBidirectional: Boolean,
    val interactionTypes: Set<InteractionType>,
)

/**
 * Complete layout of the topology graph ready for rendering.
 *
 * @property nodes All nodes with calculated positions
 * @property edges All edges between nodes
 * @property width Total width of the layout in characters
 * @property height Total height of the layout in characters
 */
data class TopologyLayout(
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge>,
    val width: Int,
    val height: Int,
)
