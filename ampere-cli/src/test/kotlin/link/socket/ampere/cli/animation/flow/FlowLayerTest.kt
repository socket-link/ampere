package link.socket.ampere.cli.animation.flow

import link.socket.ampere.cli.animation.agent.AgentLayer
import link.socket.ampere.cli.animation.agent.AgentVisualState
import link.socket.ampere.cli.animation.substrate.SubstrateState
import link.socket.ampere.cli.animation.substrate.Vector2
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowConnectionTest {

    @Test
    fun `create connection with default values`() {
        val connection = FlowConnection(
            id = "agent1->agent2",
            sourceAgentId = "agent1",
            targetAgentId = "agent2"
        )

        assertEquals("agent1->agent2", connection.id)
        assertEquals("agent1", connection.sourceAgentId)
        assertEquals("agent2", connection.targetAgentId)
        assertEquals(FlowState.DORMANT, connection.state)
        assertEquals(0f, connection.progress)
        assertNull(connection.taskToken)
        assertFalse(connection.isActive)
    }

    @Test
    fun `startHandoff initiates animation`() {
        val connection = FlowConnection(
            id = "a->b",
            sourceAgentId = "a",
            targetAgentId = "b"
        )

        val started = connection.startHandoff(Vector2(10f, 5f))

        assertEquals(FlowState.ACTIVATING, started.state)
        assertEquals(0f, started.progress)
        assertNotNull(started.taskToken)
        assertEquals(Vector2(10f, 5f), started.taskToken?.position)
    }

    @Test
    fun `completeHandoff sets received state`() {
        val connection = FlowConnection(
            id = "a->b",
            sourceAgentId = "a",
            targetAgentId = "b",
            state = FlowState.TRANSMITTING,
            taskToken = TaskToken(Vector2(50f, 10f))
        )

        val completed = connection.completeHandoff()

        assertEquals(FlowState.RECEIVED, completed.state)
        assertEquals(1f, completed.progress)
        assertEquals(TaskToken.Companion.Glyphs.COMPLETED, completed.taskToken?.glyph)
    }

    @Test
    fun `reset returns to dormant state`() {
        val connection = FlowConnection(
            id = "a->b",
            sourceAgentId = "a",
            targetAgentId = "b",
            state = FlowState.RECEIVED,
            progress = 1f,
            taskToken = TaskToken(Vector2(50f, 10f))
        )

        val reset = connection.reset()

        assertEquals(FlowState.DORMANT, reset.state)
        assertEquals(0f, reset.progress)
        assertNull(reset.taskToken)
    }

    @Test
    fun `getCurrentPathPosition returns correct position`() {
        val path = listOf(
            Vector2(0f, 0f),
            Vector2(10f, 5f),
            Vector2(20f, 10f)
        )
        val connection = FlowConnection(
            id = "a->b",
            sourceAgentId = "a",
            targetAgentId = "b",
            path = path,
            progress = 0.5f
        )

        val position = connection.getCurrentPathPosition()

        assertNotNull(position)
        assertEquals(10f, position.x, 0.1f)
    }
}

class TaskTokenTest {

    @Test
    fun `create token with default values`() {
        val token = TaskToken(position = Vector2(10f, 5f))

        assertEquals(Vector2(10f, 5f), token.position)
        assertEquals('●', token.glyph)
        assertTrue(token.trailParticles.isEmpty())
    }

    @Test
    fun `createTrailParticle creates particle at position`() {
        val token = TaskToken(position = Vector2(10f, 5f))
        val particle = token.createTrailParticle()

        assertEquals(Vector2(10f, 5f), particle.position)
        assertTrue(particle.life > 0f)
    }

    @Test
    fun `forProgress returns correct glyph`() {
        assertEquals(TaskToken.Companion.Glyphs.DELEGATING, TaskToken.Companion.Glyphs.forProgress(0f))
        assertEquals(TaskToken.Companion.Glyphs.ACTIVE, TaskToken.Companion.Glyphs.forProgress(0.5f))
        assertEquals(TaskToken.Companion.Glyphs.COMPLETED, TaskToken.Companion.Glyphs.forProgress(1f))
    }

    @Test
    fun `withNewTrailParticle adds particle to list`() {
        val token = TaskToken(position = Vector2(10f, 5f))

        val updated = token.withNewTrailParticle()

        assertEquals(1, updated.trailParticles.size)
    }

    @Test
    fun `withUpdatedTrail removes dead particles`() {
        val baseToken = TaskToken(position = Vector2(10f, 5f))
        val token = TaskToken(
            position = Vector2(10f, 5f),
            trailParticles = listOf(baseToken.createTrailParticle())
        )

        // Update with high decay rate to kill particles quickly
        val updated = token.withUpdatedTrail(deltaTime = 2f, decayRate = 1f)

        assertTrue(updated.trailParticles.isEmpty())
    }
}

class FlowPathTest {

    @Test
    fun `calculatePath returns correct number of points`() {
        val path = FlowPath.calculatePath(
            from = Vector2(0f, 0f),
            to = Vector2(20f, 10f),
            steps = 10
        )

        assertEquals(11, path.size) // steps + 1 for inclusive
    }

    @Test
    fun `calculatePath starts and ends at correct positions`() {
        val from = Vector2(0f, 0f)
        val to = Vector2(20f, 10f)
        val path = FlowPath.calculatePath(from, to)

        assertEquals(from.x, path.first().x, 0.01f)
        assertEquals(from.y, path.first().y, 0.01f)
        assertEquals(to.x, path.last().x, 0.01f)
        assertEquals(to.y, path.last().y, 0.01f)
    }

    @Test
    fun `calculateLinearPath creates straight line`() {
        val path = FlowPath.calculateLinearPath(
            from = Vector2(0f, 0f),
            to = Vector2(10f, 10f),
            steps = 10
        )

        // All points should be on the diagonal
        path.forEach { point ->
            assertEquals(point.x, point.y, 0.01f)
        }
    }

    @Test
    fun `positionAtProgress returns correct position`() {
        val path = listOf(
            Vector2(0f, 0f),
            Vector2(10f, 0f),
            Vector2(20f, 0f)
        )

        val start = FlowPath.positionAtProgress(path, 0f)
        val middle = FlowPath.positionAtProgress(path, 0.5f)
        val end = FlowPath.positionAtProgress(path, 1f)

        assertEquals(0f, start.x, 0.01f)
        assertEquals(10f, middle.x, 0.01f)
        assertEquals(20f, end.x, 0.01f)
    }

    @Test
    fun `pathLength calculates correct length`() {
        val path = listOf(
            Vector2(0f, 0f),
            Vector2(10f, 0f),
            Vector2(10f, 10f)
        )

        val length = FlowPath.pathLength(path)

        assertEquals(20f, length, 0.01f)
    }

    @Test
    fun `directionAtProgress returns correct direction`() {
        val path = listOf(
            Vector2(0f, 0f),
            Vector2(10f, 0f),
            Vector2(10f, 10f)
        )

        val dirAtStart = FlowPath.directionAtProgress(path, 0f)
        val dirAtEnd = FlowPath.directionAtProgress(path, 0.75f)

        // First segment goes right (positive x)
        assertTrue(dirAtStart.x > 0)
        assertEquals(0f, dirAtStart.y, 0.01f)

        // Last segment goes down (positive y)
        assertEquals(0f, dirAtEnd.x, 0.01f)
        assertTrue(dirAtEnd.y > 0)
    }
}

class FlowEasingTest {

    @Test
    fun `linear easing returns input unchanged`() {
        assertEquals(0f, FlowEasing.linear(0f), 0.01f)
        assertEquals(0.5f, FlowEasing.linear(0.5f), 0.01f)
        assertEquals(1f, FlowEasing.linear(1f), 0.01f)
    }

    @Test
    fun `easeInOut starts slow and ends slow`() {
        val start = FlowEasing.easeInOut(0.1f)
        val middle = FlowEasing.easeInOut(0.5f)
        val end = FlowEasing.easeInOut(0.9f)

        // At 0.1 should be slower than linear
        assertTrue(start < 0.1f)
        // At 0.5 should be roughly linear
        assertEquals(0.5f, middle, 0.1f)
        // At 0.9 should be faster than we'd be at 0.1 from end
        assertTrue(end > 0.9f)
    }

    @Test
    fun `easeIn starts slow`() {
        val early = FlowEasing.easeIn(0.2f)
        assertTrue(early < 0.2f)
    }

    @Test
    fun `easeOut ends slow`() {
        val late = FlowEasing.easeOut(0.8f)
        assertTrue(late > 0.8f)
    }

    @Test
    fun `all easing functions have correct boundaries`() {
        val easings = listOf(
            FlowEasing::linear,
            FlowEasing::easeIn,
            FlowEasing::easeOut,
            FlowEasing::easeInOut,
            FlowEasing::easeInCubic,
            FlowEasing::easeOutCubic,
            FlowEasing::easeInOutCubic
        )

        easings.forEach { easing ->
            assertEquals(0f, easing(0f), 0.01f)
            assertEquals(1f, easing(1f), 0.01f)
        }
    }
}

class FlowLayerTest {

    @Test
    fun `createConnection adds connection`() {
        val layer = FlowLayer(100, 30)

        val id = layer.createConnection(
            sourceAgentId = "agent1",
            targetAgentId = "agent2",
            sourcePosition = Vector2(10f, 5f),
            targetPosition = Vector2(90f, 25f)
        )

        assertEquals(1, layer.connectionCount)
        assertNotNull(layer.getConnection(id))
        assertEquals("agent1->agent2", id)
    }

    @Test
    fun `getConnection by agent pair works`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2.ZERO, Vector2(50f, 10f))

        val connection = layer.getConnection("a", "b")

        assertNotNull(connection)
        assertEquals("a", connection.sourceAgentId)
        assertEquals("b", connection.targetAgentId)
    }

    @Test
    fun `removeConnection removes connection`() {
        val layer = FlowLayer(100, 30)
        val id = layer.createConnection("a", "b", Vector2.ZERO, Vector2(50f, 10f))

        layer.removeConnection(id)

        assertEquals(0, layer.connectionCount)
        assertNull(layer.getConnection(id))
    }

    @Test
    fun `startHandoff begins animation`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 5f), Vector2(90f, 25f))

        val result = layer.startHandoff("a", "b")

        assertTrue(result)
        val connection = layer.getConnection("a", "b")
        assertNotNull(connection)
        assertEquals(FlowState.TRANSMITTING, connection.state)
        assertNotNull(connection.taskToken)
    }

    @Test
    fun `startHandoff returns false for nonexistent connection`() {
        val layer = FlowLayer(100, 30)

        val result = layer.startHandoff("x", "y")

        assertFalse(result)
    }

    @Test
    fun `update advances transmitting connection`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 5f), Vector2(90f, 25f))
        layer.startHandoff("a", "b")

        val initialProgress = layer.getConnection("a", "b")?.progress ?: 0f
        layer.update(deltaTime = 0.5f, transmissionSpeed = 1f)

        val newProgress = layer.getConnection("a", "b")?.progress ?: 0f
        assertTrue(newProgress > initialProgress, "Progress should advance")
    }

    @Test
    fun `update completes transmission at full progress`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 5f), Vector2(90f, 25f))
        layer.startHandoff("a", "b")

        // Run many updates to complete
        repeat(20) {
            layer.update(deltaTime = 0.1f, transmissionSpeed = 1f)
        }

        val connection = layer.getConnection("a", "b")
        assertNotNull(connection)
        assertTrue(connection.state == FlowState.RECEIVED || connection.state == FlowState.DORMANT)
    }

    @Test
    fun `update spawns trail particles during transmission`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 5f), Vector2(90f, 25f))
        layer.startHandoff("a", "b")

        // Run updates with high trail spawn rate
        repeat(10) {
            layer.update(deltaTime = 0.1f, transmissionSpeed = 0.5f, trailSpawnRate = 1f)
        }

        assertTrue(layer.particleCount > 0, "Should have spawned trail particles")
    }

    @Test
    fun `createConnectionsFromAgents creates connections from agent layer`() {
        val agentLayer = AgentLayer(100, 30)
        agentLayer.addAgent(AgentVisualState("spark", "Spark", "reasoning", Vector2(10f, 15f)))
        agentLayer.addAgent(AgentVisualState("jazz", "Jazz", "codegen", Vector2(90f, 15f)))

        val flowLayer = FlowLayer(100, 30)
        flowLayer.createConnectionsFromAgents(agentLayer, listOf("spark" to "jazz"))

        assertEquals(1, flowLayer.connectionCount)
        assertNotNull(flowLayer.getConnection("spark", "jazz"))
    }

    @Test
    fun `getActiveConnections returns only active connections`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2.ZERO, Vector2(50f, 10f))
        layer.createConnection("c", "d", Vector2(10f, 20f), Vector2(60f, 20f))

        // Start handoff on one connection only
        layer.startHandoff("a", "b")

        val active = layer.getActiveConnections()

        assertEquals(1, active.size)
        assertEquals("a->b", active[0].id)
    }

    @Test
    fun `updateSubstrate increases density along active paths`() {
        val layer = FlowLayer(100, 30)
        val sourcePos = Vector2(10f, 15f)
        val targetPos = Vector2(30f, 15f)
        layer.createConnection("a", "b", sourcePos, targetPos)
        layer.startHandoff("a", "b")

        val substrate = SubstrateState.create(100, 30)

        // Get total density before update
        var initialTotal = 0f
        for (x in 0 until substrate.width) {
            for (y in 0 until substrate.height) {
                initialTotal += substrate.getDensity(x, y)
            }
        }

        layer.updateSubstrate(substrate, influence = 0.3f)

        // Get total density after update
        var finalTotal = 0f
        for (x in 0 until substrate.width) {
            for (y in 0 until substrate.height) {
                finalTotal += substrate.getDensity(x, y)
            }
        }

        assertTrue(finalTotal > initialTotal, "Total density should increase along path")
    }

    @Test
    fun `clear removes all connections and particles`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2.ZERO, Vector2(50f, 10f))
        layer.startHandoff("a", "b")
        layer.update(deltaTime = 0.1f, trailSpawnRate = 1f)

        layer.clear()

        assertEquals(0, layer.connectionCount)
        assertEquals(0, layer.particleCount)
    }
}

class FlowLayerRendererTest {

    @Test
    fun `render returns items for active connections`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 15f), Vector2(90f, 15f))
        layer.startHandoff("a", "b")

        val renderer = FlowLayerRenderer()
        val items = renderer.render(layer)

        assertEquals(1, items.size)
        assertEquals("a->b", items[0].connectionId)
    }

    @Test
    fun `render excludes dormant connections by default`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 15f), Vector2(90f, 15f))
        // Don't start handoff - connection stays dormant

        val renderer = FlowLayerRenderer(showDormantConnections = false)
        val items = renderer.render(layer)

        assertEquals(0, items.size)
    }

    @Test
    fun `render includes dormant connections when enabled`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 15f), Vector2(90f, 15f))

        val renderer = FlowLayerRenderer(showDormantConnections = true)
        val items = renderer.render(layer)

        assertEquals(1, items.size)
    }

    @Test
    fun `renderToGrid creates correct dimensions`() {
        val layer = FlowLayer(80, 24)
        val renderer = FlowLayerRenderer()

        val grid = renderer.renderToGrid(layer, 80, 24)

        assertEquals(24, grid.size)
        grid.forEach { row ->
            assertEquals(80, row.length)
        }
    }

    @Test
    fun `render item includes path characters`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 15f), Vector2(50f, 15f))
        layer.startHandoff("a", "b")

        val renderer = FlowLayerRenderer()
        val items = renderer.render(layer)

        assertTrue(items[0].pathChars.isNotEmpty(), "Should have path characters")
    }

    @Test
    fun `render item includes token position during transmission`() {
        val layer = FlowLayer(100, 30)
        layer.createConnection("a", "b", Vector2(10f, 15f), Vector2(50f, 15f))
        layer.startHandoff("a", "b")

        val renderer = FlowLayerRenderer()
        val items = renderer.render(layer)

        assertNotNull(items[0].tokenPosition)
        assertNotNull(items[0].tokenChar)
    }

    @Test
    fun `BoxDrawing forDirection returns correct characters`() {
        val horizontal = FlowLayerRenderer.BoxDrawing.forDirection(
            FlowLayerRenderer.Direction.LEFT,
            FlowLayerRenderer.Direction.RIGHT,
            useUnicode = true
        )
        assertEquals('─', horizontal)

        val vertical = FlowLayerRenderer.BoxDrawing.forDirection(
            FlowLayerRenderer.Direction.UP,
            FlowLayerRenderer.Direction.DOWN,
            useUnicode = true
        )
        assertEquals('│', vertical)

        val corner = FlowLayerRenderer.BoxDrawing.forDirection(
            FlowLayerRenderer.Direction.DOWN,
            FlowLayerRenderer.Direction.RIGHT,
            useUnicode = true
        )
        assertEquals('╭', corner)
    }

    @Test
    fun `BoxDrawing returns ASCII when unicode disabled`() {
        val horizontal = FlowLayerRenderer.BoxDrawing.forDirection(
            FlowLayerRenderer.Direction.LEFT,
            FlowLayerRenderer.Direction.RIGHT,
            useUnicode = false
        )
        assertEquals('-', horizontal)
    }
}
