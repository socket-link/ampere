package link.socket.ampere.cli.hybrid

import kotlinx.datetime.Clock
import link.socket.ampere.animation.particle.ParticleSystem
import link.socket.ampere.animation.substrate.SubstrateAnimator
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.cli.watch.presentation.AgentActivityState
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SignificantEventSummary
import link.socket.ampere.cli.watch.presentation.SystemVitals
import link.socket.ampere.cli.watch.presentation.WatchViewState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchStateAnimationBridgeTest {

    private val now = Clock.System.now()

    private fun createBridge(
        accentColumns: Set<Int> = setOf(10, 20),
        height: Int = 24
    ): Triple<WatchStateAnimationBridge, SubstrateState, ParticleSystem> {
        val animator = SubstrateAnimator(baseDensity = 0.2f, seed = 42L)
        val particles = ParticleSystem(maxParticles = 30, lifeDecayRate = 0.5f)
        val bridge = WatchStateAnimationBridge(
            substrateAnimator = animator,
            particles = particles,
            accentColumns = accentColumns,
            height = height
        )
        val substrate = SubstrateState.create(30, 24, baseDensity = 0.2f)
        return Triple(bridge, substrate, particles)
    }

    private fun createViewState(
        agents: Map<String, AgentActivityState> = emptyMap(),
        events: List<SignificantEventSummary> = emptyList()
    ): WatchViewState {
        return WatchViewState(
            systemVitals = SystemVitals(
                activeAgentCount = agents.count { !it.value.isIdle },
                systemState = link.socket.ampere.cli.watch.presentation.SystemState.WORKING,
                lastSignificantEventTime = now
            ),
            agentStates = agents,
            recentSignificantEvents = events
        )
    }

    private fun createAgent(
        id: String,
        state: AgentState = AgentState.IDLE,
        idle: Boolean = state == AgentState.IDLE
    ): AgentActivityState {
        return AgentActivityState(
            agentId = id,
            displayName = id,
            currentState = state,
            lastActivityTimestamp = now,
            consecutiveCognitiveCycles = 0,
            isIdle = idle
        )
    }

    private fun createEvent(
        significance: EventSignificance = EventSignificance.SIGNIFICANT
    ): SignificantEventSummary {
        return SignificantEventSummary(
            eventId = "evt-${System.nanoTime()}",
            timestamp = now,
            eventType = "TestEvent",
            sourceAgentName = "test-agent",
            summaryText = "Test event",
            significance = significance
        )
    }

    @Test
    fun `null viewState returns ambient-updated substrate`() {
        val (bridge, substrate, _) = createBridge()
        val result = bridge.update(null, substrate, 0.1f)

        // Should still advance time via ambient animation
        assertTrue(result.time > substrate.time)
    }

    @Test
    fun `empty viewState does not crash`() {
        val (bridge, substrate, particles) = createBridge()
        val viewState = createViewState()

        val result = bridge.update(viewState, substrate, 0.1f)
        assertTrue(result.time > substrate.time)
        assertEquals(0, particles.count)
    }

    @Test
    fun `active agents create substrate hotspots`() {
        val (bridge, substrate, _) = createBridge()
        val agents = mapOf(
            "agent-1" to createAgent("agent-1", AgentState.WORKING, idle = false)
        )
        val viewState = createViewState(agents = agents)

        val result = bridge.update(viewState, substrate, 0.1f)
        assertTrue(result.activityHotspots.isNotEmpty())
    }

    @Test
    fun `idle agents do not create hotspots`() {
        val (bridge, substrate, _) = createBridge()
        val agents = mapOf(
            "agent-1" to createAgent("agent-1", AgentState.IDLE)
        )
        val viewState = createViewState(agents = agents)

        val result = bridge.update(viewState, substrate, 0.1f)
        assertTrue(result.activityHotspots.isEmpty())
    }

    @Test
    fun `significant events spawn particles`() {
        val (bridge, substrate, particles) = createBridge()

        // First update with no events
        val viewState1 = createViewState()
        bridge.update(viewState1, substrate, 0.1f)

        // Second update with new significant event
        val viewState2 = createViewState(
            events = listOf(createEvent(EventSignificance.SIGNIFICANT))
        )
        bridge.update(viewState2, substrate, 0.1f)

        assertTrue(particles.count > 0, "Should have spawned particles for significant event")
    }

    @Test
    fun `routine events do not spawn particles`() {
        val (bridge, substrate, particles) = createBridge()

        // First update
        bridge.update(createViewState(), substrate, 0.1f)

        // Second update with routine event only
        val viewState = createViewState(
            events = listOf(createEvent(EventSignificance.ROUTINE))
        )
        bridge.update(viewState, substrate, 0.1f)

        assertEquals(0, particles.count, "Routine events should not spawn particles")
    }

    @Test
    fun `critical events spawn more particles than significant`() {
        val (bridge1, substrate1, particles1) = createBridge()
        bridge1.update(createViewState(), substrate1, 0.1f)
        bridge1.update(
            createViewState(events = listOf(createEvent(EventSignificance.CRITICAL))),
            substrate1, 0.1f
        )
        val criticalCount = particles1.count

        val (bridge2, substrate2, particles2) = createBridge()
        bridge2.update(createViewState(), substrate2, 0.1f)
        bridge2.update(
            createViewState(events = listOf(createEvent(EventSignificance.SIGNIFICANT))),
            substrate2, 0.1f
        )
        val significantCount = particles2.count

        assertTrue(criticalCount >= significantCount, "Critical events should spawn >= particles than significant")
    }

    @Test
    fun `particle count stays bounded`() {
        val (bridge, substrate, particles) = createBridge()

        // Trigger many events
        var viewState = createViewState()
        bridge.update(viewState, substrate, 0.1f)

        for (i in 1..50) {
            viewState = createViewState(
                events = (1..i).map { createEvent(EventSignificance.CRITICAL) }
            )
            bridge.update(viewState, substrate, 0.1f)
        }

        assertTrue(particles.count <= 30, "Particles should be bounded at max 30, got ${particles.count}")
    }

    @Test
    fun `choreographer triggers on cognitive phase transition`() {
        val (bridge, substrate, particles) = createBridge()

        // First update: agent is THINKING (maps to PLAN phase)
        val viewState1 = createViewState(
            agents = mapOf("agent-1" to createAgent("agent-1", AgentState.THINKING, idle = false))
        )
        bridge.update(viewState1, substrate, 0.1f)
        val particlesAfterThinking = particles.count

        // Second update: agent transitions to WORKING (maps to EXECUTE phase)
        // EXECUTE transition should trigger a spark burst via choreographer
        val viewState2 = createViewState(
            agents = mapOf("agent-1" to createAgent("agent-1", AgentState.WORKING, idle = false))
        )
        bridge.update(viewState2, substrate, 0.1f)

        assertTrue(
            particles.count > particlesAfterThinking,
            "Phase transition to EXECUTE should trigger particle burst via choreographer"
        )
    }

    @Test
    fun `agent state transition triggers pulse`() {
        val (bridge, substrate, _) = createBridge()

        // First update: agent is idle
        val viewState1 = createViewState(
            agents = mapOf("agent-1" to createAgent("agent-1", AgentState.IDLE))
        )
        bridge.update(viewState1, substrate, 0.1f)

        // Second update: agent transitions to working
        val viewState2 = createViewState(
            agents = mapOf("agent-1" to createAgent("agent-1", AgentState.WORKING, idle = false))
        )
        val result = bridge.update(viewState2, substrate, 0.1f)

        // Should have increased density somewhere via pulse
        var maxDensity = 0f
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                maxDensity = maxOf(maxDensity, result.getDensity(x, y))
            }
        }
        assertTrue(maxDensity > substrate.getDensity(0, 0) + 0.05f,
            "State transition should create a density pulse")
    }
}
