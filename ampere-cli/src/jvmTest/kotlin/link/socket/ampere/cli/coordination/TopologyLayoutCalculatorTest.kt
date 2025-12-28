package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.coordination.AgentInteraction
import link.socket.ampere.coordination.CoordinationEdge
import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.InteractionType

class TopologyLayoutCalculatorTest {

    private val calculator = TopologyLayoutCalculator()

    @Test
    fun `three agents are laid out in grid`() {
        val state = createStateWith3Agents()
        val agentStates = mapOf(
            "agent-A" to NodeState.ACTIVE,
            "agent-B" to NodeState.IDLE,
            "agent-C" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        assertEquals(3, layout.nodes.size, "Should have 3 nodes")
        assertTrue(layout.nodes.all { it.position.x >= 0 && it.position.y >= 0 }, "All positions should be non-negative")
        assertTrue(layout.width > 0, "Width should be positive")
        assertTrue(layout.height > 0, "Height should be positive")
    }

    @Test
    fun `human agent positioned at bottom`() {
        val state = createStateWithHumanEscalation()
        val agentStates = mapOf(
            "agent-A" to NodeState.ACTIVE,
            "agent-B" to NodeState.IDLE,
            "human" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        val humanNode = layout.nodes.find { it.isHuman }
        val otherNodes = layout.nodes.filter { !it.isHuman }

        assertNotNull(humanNode, "Should have a human node")
        assertTrue(otherNodes.all { humanNode.position.y > it.position.y }, "Human should be below all other nodes")
    }

    @Test
    fun `agent names are shortened correctly`() {
        val state = createStateWith3Agents()
        val agentStates = mapOf(
            "agent-A" to NodeState.ACTIVE,
            "agent-B" to NodeState.IDLE,
            "agent-C" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        // Names should be shortened (e.g., "CodeWriterAgent" becomes "CodeWriter")
        val nodeA = layout.nodes.find { it.agentId == "agent-A" }
        assertNotNull(nodeA)
        assertEquals("agent-A", nodeA.displayName) // Simple names don't get suffixes removed
    }

    @Test
    fun `agent suffix is removed from display names`() {
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge(
                    sourceAgentId = "CodeWriterAgent",
                    targetAgentId = "ReviewerAgent",
                    interactionCount = 5,
                    lastInteraction = Clock.System.now(),
                    interactionTypes = setOf(InteractionType.REVIEW_REQUEST),
                ),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = Clock.System.now(),
        )

        val agentStates = mapOf(
            "CodeWriterAgent" to NodeState.ACTIVE,
            "ReviewerAgent" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        val codeWriterNode = layout.nodes.find { it.agentId == "CodeWriterAgent" }
        val reviewerNode = layout.nodes.find { it.agentId == "ReviewerAgent" }

        assertNotNull(codeWriterNode)
        assertNotNull(reviewerNode)

        assertEquals("CodeWriter", codeWriterNode.displayName)
        assertEquals("Reviewer", reviewerNode.displayName)
    }

    @Test
    fun `bidirectional edges are detected`() {
        val state = createStateWithBidirectionalEdge()
        val agentStates = mapOf(
            "agent-A" to NodeState.ACTIVE,
            "agent-B" to NodeState.ACTIVE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        assertEquals(1, layout.edges.size, "Should consolidate bidirectional edges into one")
        val edge = layout.edges.first()
        assertTrue(edge.isBidirectional, "Edge should be marked as bidirectional")
    }

    @Test
    fun `unidirectional edge is not bidirectional`() {
        val state = createStateWith3Agents()
        val agentStates = mapOf(
            "agent-A" to NodeState.ACTIVE,
            "agent-B" to NodeState.IDLE,
            "agent-C" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        assertTrue(layout.edges.all { !it.isBidirectional }, "Unidirectional edges should not be marked as bidirectional")
    }

    @Test
    fun `empty state produces empty layout`() {
        val state = CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = Clock.System.now(),
        )

        val layout = calculator.calculateLayout(state, emptyMap())

        assertEquals(0, layout.nodes.size)
        assertEquals(0, layout.edges.size)
    }

    @Test
    fun `agents are ranked by connectivity`() {
        val state = createStateWithVaryingConnectivity()
        val agentStates = mapOf(
            "agent-hub" to NodeState.ACTIVE,
            "agent-leaf1" to NodeState.IDLE,
            "agent-leaf2" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        // The hub agent should be positioned first (most connected)
        val hubNode = layout.nodes.find { it.agentId == "agent-hub" }
        assertNotNull(hubNode)

        // Hub should be at position (0,0) since it's most connected
        assertEquals(0, hubNode.position.x)
        assertEquals(0, hubNode.position.y)
    }

    @Test
    fun `interaction types are abbreviated in edge labels`() {
        val state = createStateWith3Agents()
        val agentStates = mapOf(
            "agent-A" to NodeState.ACTIVE,
            "agent-B" to NodeState.IDLE,
            "agent-C" to NodeState.IDLE,
        )

        val layout = calculator.calculateLayout(state, agentStates)

        assertTrue(layout.edges.all { it.label.isNotEmpty() }, "All edges should have labels")
        assertTrue(layout.edges.all { it.label.length <= 6 }, "Labels should be abbreviated")
    }

    // Helper methods to create test data

    private fun createStateWith3Agents(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = listOf(
                CoordinationEdge(
                    sourceAgentId = "agent-A",
                    targetAgentId = "agent-B",
                    interactionCount = 3,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.TICKET_ASSIGNED),
                ),
                CoordinationEdge(
                    sourceAgentId = "agent-B",
                    targetAgentId = "agent-C",
                    interactionCount = 2,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.DELEGATION),
                ),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStateWithHumanEscalation(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = listOf(
                CoordinationEdge(
                    sourceAgentId = "agent-A",
                    targetAgentId = "agent-B",
                    interactionCount = 2,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.HELP_REQUEST),
                ),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = listOf(
                AgentInteraction(
                    sourceAgentId = "agent-A",
                    targetAgentId = null,
                    interactionType = InteractionType.HUMAN_ESCALATION,
                    timestamp = now,
                    eventId = "evt-1",
                    context = "Need help",
                ),
            ),
            lastUpdated = now,
        )
    }

    private fun createStateWithBidirectionalEdge(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = listOf(
                CoordinationEdge(
                    sourceAgentId = "agent-A",
                    targetAgentId = "agent-B",
                    interactionCount = 3,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.TICKET_ASSIGNED),
                ),
                CoordinationEdge(
                    sourceAgentId = "agent-B",
                    targetAgentId = "agent-A",
                    interactionCount = 2,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.REVIEW_REQUEST),
                ),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStateWithVaryingConnectivity(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = listOf(
                // Hub agent has 2 connections
                CoordinationEdge(
                    sourceAgentId = "agent-hub",
                    targetAgentId = "agent-leaf1",
                    interactionCount = 5,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.DELEGATION),
                ),
                CoordinationEdge(
                    sourceAgentId = "agent-hub",
                    targetAgentId = "agent-leaf2",
                    interactionCount = 3,
                    lastInteraction = now,
                    interactionTypes = setOf(InteractionType.TICKET_ASSIGNED),
                ),
                // Leaf agents have only 1 connection each
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }
}
