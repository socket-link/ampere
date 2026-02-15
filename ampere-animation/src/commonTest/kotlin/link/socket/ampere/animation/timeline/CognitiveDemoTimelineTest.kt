package link.socket.ampere.animation.timeline

import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.flow.FlowLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CognitiveDemoTimelineTest {

    private fun createTimeline(): Timeline {
        val agents = AgentLayer(80, 40, AgentLayoutOrientation.CIRCULAR)
        val flow = FlowLayer(80, 40)
        return CognitiveDemoTimeline.build(agents, flow)
    }

    @Test
    fun `timeline builds successfully`() {
        val timeline = createTimeline()
        assertTrue(timeline.phaseCount > 0)
    }

    @Test
    fun `timeline has correct number of phases`() {
        val timeline = createTimeline()
        assertEquals(7, timeline.phaseCount)
    }

    @Test
    fun `timeline has correct total duration`() {
        val timeline = createTimeline()
        val expectedDuration = CognitiveDemoTimeline.TOTAL_DURATION_SECONDS.seconds
        assertEquals(expectedDuration, timeline.totalDuration)
    }

    @Test
    fun `phase ordering matches PROPEL cycle`() {
        val timeline = createTimeline()
        val phaseNames = timeline.phases.map { it.name }
        assertEquals(CognitiveDemoTimeline.PHASE_NAMES, phaseNames)
    }

    @Test
    fun `all expected phases are present`() {
        val timeline = createTimeline()
        for (name in CognitiveDemoTimeline.PHASE_NAMES) {
            assertNotNull(timeline.getPhase(name), "Missing phase: $name")
        }
    }

    @Test
    fun `spawn phase has correct duration`() {
        val timeline = createTimeline()
        val spawn = timeline.getPhase("spawn")
        assertNotNull(spawn)
        assertEquals(2.seconds, spawn.duration)
    }

    @Test
    fun `execute phase has correct duration`() {
        val timeline = createTimeline()
        val execute = timeline.getPhase("execute")
        assertNotNull(execute)
        assertEquals(3.seconds, execute.duration)
    }

    @Test
    fun `plan-and-delegate phase has keyframes`() {
        val timeline = createTimeline()
        val plan = timeline.getPhase("plan-and-delegate")
        assertNotNull(plan)
        assertTrue(plan.keyframes.isNotEmpty(), "plan-and-delegate should have keyframes")
    }

    @Test
    fun `timeline callbacks populate agents when invoked`() {
        val agents = AgentLayer(80, 40, AgentLayoutOrientation.CIRCULAR)
        val flow = FlowLayer(80, 40)
        val timeline = CognitiveDemoTimeline.build(agents, flow)

        assertEquals(0, agents.agentCount, "No agents before timeline starts")

        // Fire the spawn phase onStart
        timeline.phases[0].onStart?.invoke()
        assertEquals(1, agents.agentCount, "Spark should be added after spawn onStart")
        assertNotNull(agents.getAgent("spark"))
    }
}
