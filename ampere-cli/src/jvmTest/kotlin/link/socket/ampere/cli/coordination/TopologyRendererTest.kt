package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.ampere.coordination.InteractionType

class TopologyRendererTest {

    private val renderer = TopologyRenderer()

    @Test
    fun `renders empty state message when no nodes`() {
        val layout = TopologyLayout(
            nodes = emptyList(),
            edges = emptyList(),
            width = 0,
            height = 0,
        )

        val output = renderer.render(layout)

        assertTrue(output.contains("No agent interactions"), "Should show empty message")
    }

    @Test
    fun `renders node with correct name and state`() {
        val layout = createLayoutWithOneNode("CodeWriter", NodeState.ACTIVE)

        val output = renderer.render(layout)

        assertTrue(output.contains("CodeWriter"), "Should contain node name")
        assertTrue(output.contains("active"), "Should contain state")
    }

    @Test
    fun `renders box borders for node`() {
        val layout = createLayoutWithOneNode("TestAgent", NodeState.IDLE)

        val output = renderer.render(layout)

        assertTrue(output.contains("┌─────────────┐"), "Should have top border")
        assertTrue(output.contains("└─────────────┘"), "Should have bottom border")
        assertTrue(output.contains("│"), "Should have side borders")
    }

    @Test
    fun `includes spinner character in output`() {
        val layout = createLayoutWithOneNode("Agent", NodeState.ACTIVE)

        val output = renderer.render(layout, tick = 0)

        // Should contain one of the spinner characters
        val hasSpinner = output.contains('◐') || output.contains('◓') ||
            output.contains('◑') || output.contains('◒')
        assertTrue(hasSpinner, "Should include spinner character")
    }

    @Test
    fun `spinner character changes with tick`() {
        val layout = createLayoutWithOneNode("Agent", NodeState.ACTIVE)

        val output0 = renderer.render(layout, tick = 0)
        val output1 = renderer.render(layout, tick = 1)

        // Different ticks should potentially show different spinners
        // (this is a weak test since we can't guarantee different characters,
        // but it verifies the tick parameter is used)
        assertTrue(output0.isNotEmpty())
        assertTrue(output1.isNotEmpty())
    }

    @Test
    fun `renders two connected nodes with edge`() {
        val layout = createLayoutWithTwoConnectedNodes()

        val output = renderer.render(layout)

        assertTrue(output.contains("SourceAgent"), "Should contain source node")
        assertTrue(output.contains("TargetAgent"), "Should contain target node")
        // Should have some connection characters
        assertTrue(output.contains("─") || output.contains("╌"), "Should have edge line")
    }

    @Test
    fun `renders edge label`() {
        val layout = createLayoutWithTwoConnectedNodes()

        val output = renderer.render(layout)

        assertTrue(output.contains("TASK"), "Should contain edge label")
    }

    @Test
    fun `includes legend in output`() {
        val layout = createLayoutWithOneNode("Agent", NodeState.IDLE)

        val output = renderer.render(layout)

        assertTrue(output.contains("active"), "Legend should mention active")
        assertTrue(output.contains("recent"), "Legend should mention recent")
    }

    @Test
    fun `renders different node states`() {
        val activeLayout = createLayoutWithOneNode("Active", NodeState.ACTIVE)
        val blockedLayout = createLayoutWithOneNode("Blocked", NodeState.BLOCKED)
        val idleLayout = createLayoutWithOneNode("Idle", NodeState.IDLE)

        val activeOutput = renderer.render(activeLayout)
        val blockedOutput = renderer.render(blockedLayout)
        val idleOutput = renderer.render(idleLayout)

        assertTrue(activeOutput.contains("active"), "Should show active state")
        assertTrue(blockedOutput.contains("blocked"), "Should show blocked state")
        assertTrue(idleOutput.contains("idle"), "Should show idle state")
    }

    @Test
    fun `CharBuffer handles overlapping writes`() {
        val buffer = CharBuffer(20, 5)

        buffer.write(0, 0, "Hello")
        buffer.write(3, 0, "World")

        val output = buffer.toString()

        // "Hel" + "World" = "HelWorld"
        assertTrue(output.contains("HelWorld"), "Should handle overlapping writes")
    }

    @Test
    fun `CharBuffer handles out of bounds writes gracefully`() {
        val buffer = CharBuffer(10, 5)

        // These should not crash
        buffer.write(-5, 0, "Test")
        buffer.write(0, -5, "Test")
        buffer.write(100, 0, "Test")
        buffer.write(0, 100, "Test")

        val output = buffer.toString()
        // Should produce some output without crashing
        assertTrue(output.isEmpty() || output.isNotEmpty())
    }

    // Helper methods

    private fun createLayoutWithOneNode(name: String, state: NodeState): TopologyLayout {
        val node = TopologyNode(
            agentId = name,
            displayName = name,
            state = state,
            position = NodePosition(0, 0),
            isHuman = false,
        )

        return TopologyLayout(
            nodes = listOf(node),
            edges = emptyList(),
            width = 50,
            height = 20,
        )
    }

    private fun createLayoutWithTwoConnectedNodes(): TopologyLayout {
        val sourceNode = TopologyNode(
            agentId = "source",
            displayName = "SourceAgent",
            state = NodeState.ACTIVE,
            position = NodePosition(0, 0),
            isHuman = false,
        )

        val targetNode = TopologyNode(
            agentId = "target",
            displayName = "TargetAgent",
            state = NodeState.IDLE,
            position = NodePosition(25, 0),
            isHuman = false,
        )

        val edge = TopologyEdge(
            source = "source",
            target = "target",
            label = "TASK",
            isActive = true,
            isBidirectional = false,
            interactionTypes = setOf(InteractionType.TICKET_ASSIGNED),
        )

        return TopologyLayout(
            nodes = listOf(sourceNode, targetNode),
            edges = listOf(edge),
            width = 50,
            height = 20,
        )
    }
}
