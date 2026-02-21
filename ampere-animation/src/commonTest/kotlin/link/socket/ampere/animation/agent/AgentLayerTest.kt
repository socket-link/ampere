package link.socket.ampere.animation.agent

import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.substrate.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentVisualStateTest {

    @Test
    fun `create agent with default values`() {
        val agent = AgentVisualState(
            id = "agent-1",
            name = "Spark",
            role = "reasoning",
            position = Vector2(10f, 5f)
        )

        assertEquals("agent-1", agent.id)
        assertEquals("Spark", agent.name)
        assertEquals("reasoning", agent.role)
        assertEquals(AgentActivityState.IDLE, agent.state)
        assertEquals("", agent.statusText)
        assertEquals(0f, agent.pulsePhase)
    }

    @Test
    fun `withPosition creates copy with new position`() {
        val agent = AgentVisualState(
            id = "agent-1",
            name = "Spark",
            role = "reasoning",
            position = Vector2(0f, 0f)
        )

        val updated = agent.withPosition(Vector2(20f, 10f))

        assertEquals(Vector2(0f, 0f), agent.position) // Original unchanged
        assertEquals(Vector2(20f, 10f), updated.position)
    }

    @Test
    fun `withState creates copy with new state`() {
        val agent = AgentVisualState(
            id = "agent-1",
            name = "Spark",
            role = "reasoning",
            position = Vector2.ZERO
        )

        val updated = agent.withState(AgentActivityState.PROCESSING)

        assertEquals(AgentActivityState.IDLE, agent.state)
        assertEquals(AgentActivityState.PROCESSING, updated.state)
    }

    @Test
    fun `withStatus creates copy with new status`() {
        val agent = AgentVisualState(
            id = "agent-1",
            name = "Spark",
            role = "reasoning",
            position = Vector2.ZERO
        )

        val updated = agent.withStatus("analyzing...")

        assertEquals("", agent.statusText)
        assertEquals("analyzing...", updated.statusText)
    }

    @Test
    fun `withPulsePhase wraps phase value`() {
        val agent = AgentVisualState(
            id = "agent-1",
            name = "Spark",
            role = "reasoning",
            position = Vector2.ZERO
        )

        val updated = agent.withPulsePhase(1.5f)

        assertEquals(0.5f, updated.pulsePhase, 0.01f)
    }

    @Test
    fun `getPrimaryGlyph returns correct glyph for state`() {
        val idleAgent = AgentVisualState("1", "A", "r", Vector2.ZERO, state = AgentActivityState.IDLE)
        val activeAgent = AgentVisualState("2", "B", "r", Vector2.ZERO, state = AgentActivityState.ACTIVE)
        val completeAgent = AgentVisualState("3", "C", "r", Vector2.ZERO, state = AgentActivityState.COMPLETE)

        assertEquals(AgentGlyphs.IDLE_UNICODE, idleAgent.getPrimaryGlyph(useUnicode = true))
        assertEquals(AgentGlyphs.ACTIVE_UNICODE, activeAgent.getPrimaryGlyph(useUnicode = true))
        assertEquals(AgentGlyphs.COMPLETE_UNICODE, completeAgent.getPrimaryGlyph(useUnicode = true))
    }

    @Test
    fun `getAccentSuffix returns checkmark for COMPLETE`() {
        val completeAgent = AgentVisualState("1", "A", "r", Vector2.ZERO, state = AgentActivityState.COMPLETE)
        val activeAgent = AgentVisualState("2", "B", "r", Vector2.ZERO, state = AgentActivityState.ACTIVE)

        assertTrue(completeAgent.getAccentSuffix(useUnicode = true).contains("\u2713"))
        assertEquals("", activeAgent.getAccentSuffix())
    }
}

class AgentGlyphsTest {

    @Test
    fun `forState returns correct unicode glyphs`() {
        assertEquals(AgentGlyphs.SPAWNING_UNICODE, AgentGlyphs.forState(AgentActivityState.SPAWNING, true))
        assertEquals(AgentGlyphs.IDLE_UNICODE, AgentGlyphs.forState(AgentActivityState.IDLE, true))
        assertEquals(AgentGlyphs.ACTIVE_UNICODE, AgentGlyphs.forState(AgentActivityState.ACTIVE, true))
    }

    @Test
    fun `forState returns correct ascii glyphs`() {
        assertEquals(AgentGlyphs.SPAWNING_ASCII, AgentGlyphs.forState(AgentActivityState.SPAWNING, false))
        assertEquals(AgentGlyphs.IDLE_ASCII, AgentGlyphs.forState(AgentActivityState.IDLE, false))
        assertEquals(AgentGlyphs.ACTIVE_ASCII, AgentGlyphs.forState(AgentActivityState.ACTIVE, false))
    }

    @Test
    fun `spawningGlyph returns gradient based on progress`() {
        val start = AgentGlyphs.spawningGlyph(0f, true)
        val middle = AgentGlyphs.spawningGlyph(0.5f, true)
        val end = AgentGlyphs.spawningGlyph(1f, true)

        assertEquals(AgentGlyphs.SPAWNING_GRADIENT_UNICODE[0], start)
        assertEquals(AgentGlyphs.SPAWNING_GRADIENT_UNICODE[1], middle)
        assertEquals(AgentGlyphs.SPAWNING_GRADIENT_UNICODE[2], end)
    }
}

class AgentColorsTest {

    @Test
    fun `forState returns ANSI color codes`() {
        val idleColor = AgentColors.forState(AgentActivityState.IDLE)
        val activeColor = AgentColors.forState(AgentActivityState.ACTIVE)

        assertTrue(idleColor.startsWith("\u001B["))
        assertTrue(activeColor.startsWith("\u001B["))
        assertTrue(idleColor != activeColor)
    }

    @Test
    fun `forRole returns role-specific colors`() {
        val reasoningColor = AgentColors.forRole("reasoning")
        val codegenColor = AgentColors.forRole("codegen")

        assertTrue(reasoningColor.startsWith("\u001B["))
        assertTrue(codegenColor.startsWith("\u001B["))
    }
}

class AgentLayerTest {

    @Test
    fun `addAgent adds agent to layer`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("agent-1", "Spark", "reasoning", Vector2.ZERO)

        layer.addAgent(agent)

        assertEquals(1, layer.agentCount)
        assertNotNull(layer.getAgent("agent-1"))
    }

    @Test
    fun `removeAgent removes agent from layer`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("agent-1", "Spark", "reasoning", Vector2.ZERO)

        layer.addAgent(agent)
        layer.removeAgent("agent-1")

        assertEquals(0, layer.agentCount)
        assertNull(layer.getAgent("agent-1"))
    }

    @Test
    fun `updateAgentState changes agent state`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("agent-1", "Spark", "reasoning", Vector2.ZERO)

        layer.addAgent(agent)
        layer.updateAgentState("agent-1", AgentActivityState.ACTIVE)

        assertEquals(AgentActivityState.ACTIVE, layer.getAgent("agent-1")?.state)
    }

    @Test
    fun `updateAgentStatus changes agent status text`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("agent-1", "Spark", "reasoning", Vector2.ZERO)

        layer.addAgent(agent)
        layer.updateAgentStatus("agent-1", "processing task...")

        assertEquals("processing task...", layer.getAgent("agent-1")?.statusText)
    }

    @Test
    fun `horizontal layout positions agents across width`() {
        val layer = AgentLayer(100, 30, AgentLayoutOrientation.HORIZONTAL)

        layer.addAgent(AgentVisualState("1", "A", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("2", "B", "r", Vector2.ZERO))

        val positions = layer.allAgents.map { it.position }

        // First agent should be at 1/3 of width, second at 2/3
        assertEquals(2, positions.size)
        assertTrue(positions[0].x < positions[1].x, "Agents should be positioned left to right")
    }

    @Test
    fun `vertical layout positions agents down height`() {
        val layer = AgentLayer(100, 30, AgentLayoutOrientation.VERTICAL)

        layer.addAgent(AgentVisualState("1", "A", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("2", "B", "r", Vector2.ZERO))

        val positions = layer.allAgents.map { it.position }

        assertEquals(2, positions.size)
        assertTrue(positions[0].y < positions[1].y, "Agents should be positioned top to bottom")
    }

    @Test
    fun `circular layout positions agents in XZ plane with 3D coordinates`() {
        val layer = AgentLayer(100, 100, AgentLayoutOrientation.CIRCULAR)

        // Add 4 agents for quadrant positions
        layer.addAgent(AgentVisualState("1", "A", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("2", "B", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("3", "C", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("4", "D", "r", Vector2.ZERO))

        val positions3D = layer.allAgents.map { it.position3D }

        // All agents should be at Y=0 (height controlled by waveform)
        positions3D.forEach { p ->
            assertEquals(0f, p.y, 0.01f)
        }

        // All agents should be roughly equal distance from origin in XZ plane
        val distances = positions3D.map { p ->
            kotlin.math.sqrt(p.x * p.x + p.z * p.z)
        }

        val avgDistance = distances.average()
        distances.forEach { d ->
            assertEquals(avgDistance.toFloat(), d, 1f)
        }

        // 2D position should be derived from 3D (x, z) mapping
        layer.allAgents.forEach { agent ->
            assertEquals(agent.position3D.x, agent.position.x, 0.01f)
            assertEquals(agent.position3D.z, agent.position.y, 0.01f)
        }
    }

    @Test
    fun `update advances pulse phase for processing agents`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("1", "A", "r", Vector2.ZERO, state = AgentActivityState.PROCESSING)

        layer.addAgent(agent)
        layer.update(deltaTime = 0.1f, shimmerSpeed = 2f)

        val updated = layer.getAgent("1")
        assertNotNull(updated)
        assertTrue(updated.pulsePhase > 0f, "Pulse phase should advance")
    }

    @Test
    fun `update progresses spawning agents`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("1", "A", "r", Vector2.ZERO, state = AgentActivityState.SPAWNING)

        layer.addAgent(agent)
        assertEquals(0f, layer.getSpawnProgress("1"))

        layer.update(deltaTime = 0.5f, spawnSpeed = 1f)

        assertEquals(0.5f, layer.getSpawnProgress("1"), 0.01f)
    }

    @Test
    fun `spawning completes and transitions to IDLE`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("1", "A", "r", Vector2.ZERO, state = AgentActivityState.SPAWNING)

        layer.addAgent(agent)

        // Run enough updates to complete spawn
        repeat(10) {
            layer.update(deltaTime = 0.2f, spawnSpeed = 1f)
        }

        assertEquals(AgentActivityState.IDLE, layer.getAgent("1")?.state)
        assertEquals(1f, layer.getSpawnProgress("1"), 0.01f)
    }

    @Test
    fun `setAgentPosition sets custom position`() {
        val layer = AgentLayer(100, 30, AgentLayoutOrientation.CUSTOM)
        val agent = AgentVisualState("1", "A", "r", Vector2.ZERO)

        layer.addAgent(agent)
        layer.setAgentPosition("1", Vector2(25f, 15f))

        assertEquals(Vector2(25f, 15f), layer.getAgent("1")?.position)
    }

    @Test
    fun `clear removes all agents`() {
        val layer = AgentLayer(100, 30)

        layer.addAgent(AgentVisualState("1", "A", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("2", "B", "r", Vector2.ZERO))
        layer.clear()

        assertEquals(0, layer.agentCount)
    }

    @Test
    fun `sphere layout distributes agents with Y variation`() {
        val layer = AgentLayer(100, 100, AgentLayoutOrientation.SPHERE)

        layer.addAgent(AgentVisualState("1", "A", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("2", "B", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("3", "C", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("4", "D", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("5", "E", "r", Vector2.ZERO))
        layer.addAgent(AgentVisualState("6", "F", "r", Vector2.ZERO))

        val positions3D = layer.allAgents.map { it.position3D }

        // Should have meaningful Y variation (not all at Y=0)
        val yValues = positions3D.map { it.y }
        val yRange = yValues.max() - yValues.min()
        assertTrue(yRange > 1f, "SPHERE should distribute agents with meaningful Y variation, got range=$yRange")

        // All agents should be roughly equal distance from origin
        val distances = positions3D.map { it.length() }
        val avgDistance = distances.average()
        distances.forEach { d ->
            assertEquals(avgDistance.toFloat(), d, 2f)
        }

        // 2D position should be derived from XZ projection
        layer.allAgents.forEach { agent ->
            assertEquals(agent.position3D.x, agent.position.x, 0.01f)
            assertEquals(agent.position3D.z, agent.position.y, 0.01f)
        }
    }

    @Test
    fun `clustered layout groups agents by role at different depths`() {
        val layer = AgentLayer(100, 100, AgentLayoutOrientation.CLUSTERED)

        // Two roles: reasoning and codegen
        layer.addAgent(AgentVisualState("1", "Spark", "reasoning", Vector2.ZERO))
        layer.addAgent(AgentVisualState("2", "Nova", "reasoning", Vector2.ZERO))
        layer.addAgent(AgentVisualState("3", "Jazz", "codegen", Vector2.ZERO))
        layer.addAgent(AgentVisualState("4", "Riff", "codegen", Vector2.ZERO))

        val agents = layer.allAgents

        // Agents of the same role should be near each other in Z
        val reasoningZ = agents.filter { it.role == "reasoning" }.map { it.position3D.z }
        val codegenZ = agents.filter { it.role == "codegen" }.map { it.position3D.z }

        // Within-cluster Z variation should be small
        val reasoningZRange = reasoningZ.max() - reasoningZ.min()
        val codegenZRange = codegenZ.max() - codegenZ.min()
        assertTrue(reasoningZRange < 20f, "Same-role agents should be clustered in Z")
        assertTrue(codegenZRange < 20f, "Same-role agents should be clustered in Z")

        // Different clusters should have different Z centers
        val reasoningZCenter = reasoningZ.average()
        val codegenZCenter = codegenZ.average()
        assertTrue(
            kotlin.math.abs(reasoningZCenter - codegenZCenter) > 1f,
            "Different role clusters should be at different depths"
        )

        // Y should be 0 (waveform controls height)
        agents.forEach { agent ->
            assertEquals(0f, agent.position3D.y, 0.01f)
        }
    }

    @Test
    fun `setAgentPosition3D sets custom 3D position`() {
        val layer = AgentLayer(100, 30, AgentLayoutOrientation.CUSTOM)
        val agent = AgentVisualState("1", "A", "r", Vector2.ZERO)

        layer.addAgent(agent)
        layer.setAgentPosition3D("1", Vector3(10f, 5f, 20f))

        val updated = layer.getAgent("1")
        assertNotNull(updated)
        assertEquals(Vector3(10f, 5f, 20f), updated.position3D)
        // 2D position should be XZ projection
        assertEquals(10f, updated.position.x, 0.01f)
        assertEquals(20f, updated.position.y, 0.01f)
    }
}

class AgentVisualState3DTest {

    @Test
    fun `default position3D derived from position2D`() {
        val agent = AgentVisualState(
            id = "1",
            name = "A",
            role = "r",
            position = Vector2(10f, 20f)
        )

        assertEquals(10f, agent.position3D.x, 0.01f)
        assertEquals(0f, agent.position3D.y, 0.01f)
        assertEquals(20f, agent.position3D.z, 0.01f)
    }

    @Test
    fun `explicit position3D preserves both fields`() {
        val agent = AgentVisualState(
            id = "1",
            name = "A",
            role = "r",
            position = Vector2(10f, 20f),
            position3D = Vector3(10f, 5f, 20f)
        )

        assertEquals(Vector2(10f, 20f), agent.position)
        assertEquals(Vector3(10f, 5f, 20f), agent.position3D)
    }

    @Test
    fun `withPosition updates position3D keeping Y`() {
        val agent = AgentVisualState(
            id = "1",
            name = "A",
            role = "r",
            position = Vector2(10f, 20f),
            position3D = Vector3(10f, 5f, 20f)
        )

        val updated = agent.withPosition(Vector2(30f, 40f))

        assertEquals(Vector2(30f, 40f), updated.position)
        assertEquals(30f, updated.position3D.x, 0.01f)
        assertEquals(5f, updated.position3D.y, 0.01f) // Y preserved
        assertEquals(40f, updated.position3D.z, 0.01f)
    }

    @Test
    fun `withPosition3D updates both position and position3D`() {
        val agent = AgentVisualState(
            id = "1",
            name = "A",
            role = "r",
            position = Vector2.ZERO
        )

        val updated = agent.withPosition3D(Vector3(15f, 8f, 25f))

        assertEquals(Vector3(15f, 8f, 25f), updated.position3D)
        // 2D position is XZ projection
        assertEquals(15f, updated.position.x, 0.01f)
        assertEquals(25f, updated.position.y, 0.01f)
    }
}

class AgentLayerRendererTest {

    @Test
    fun `render returns items for all agents`() {
        val layer = AgentLayer(100, 30)
        layer.addAgent(AgentVisualState("1", "Spark", "reasoning", Vector2(10f, 5f)))
        layer.addAgent(AgentVisualState("2", "Jazz", "codegen", Vector2(50f, 5f)))

        val renderer = AgentLayerRenderer()
        val items = renderer.render(layer)

        assertEquals(2, items.size)
        assertEquals("1", items[0].agentId)
        assertEquals("2", items[1].agentId)
    }

    @Test
    fun `render item includes position`() {
        val layer = AgentLayer(100, 30, AgentLayoutOrientation.CUSTOM)
        layer.addAgent(AgentVisualState("1", "Spark", "reasoning", Vector2(25f, 10f)))

        val renderer = AgentLayerRenderer()
        val items = renderer.render(layer)

        assertEquals(Vector2(25f, 10f), items[0].position)
    }

    @Test
    fun `render item includes node display with name`() {
        val layer = AgentLayer(100, 30)
        layer.addAgent(AgentVisualState("1", "Spark", "reasoning", Vector2.ZERO))

        val renderer = AgentLayerRenderer()
        val items = renderer.render(layer)

        assertTrue(items[0].nodeDisplay.contains("Spark"))
    }

    @Test
    fun `render item includes status display when present`() {
        val layer = AgentLayer(100, 30)
        val agent = AgentVisualState("1", "Spark", "reasoning", Vector2.ZERO, statusText = "analyzing...")
        layer.addAgent(agent)

        val renderer = AgentLayerRenderer(showStatusText = true)
        val items = renderer.render(layer)

        assertNotNull(items[0].statusDisplay)
        assertTrue(items[0].statusDisplay!!.contains("analyzing..."))
    }

    @Test
    fun `render item has no status display when status is empty`() {
        val layer = AgentLayer(100, 30)
        layer.addAgent(AgentVisualState("1", "Spark", "reasoning", Vector2.ZERO))

        val renderer = AgentLayerRenderer()
        val items = renderer.render(layer)

        assertNull(items[0].statusDisplay)
    }

    @Test
    fun `renderToGrid creates correct dimensions`() {
        val layer = AgentLayer(80, 24)
        val renderer = AgentLayerRenderer()

        val grid = renderer.renderToGrid(layer, 80, 24)

        assertEquals(24, grid.size)
        grid.forEach { row ->
            assertEquals(80, row.length)
        }
    }
}
