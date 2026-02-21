package link.socket.ampere.animation.timeline

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.emitter.EmitterManager
import link.socket.ampere.animation.flow.FlowLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WaveformDemoTimelineTest {

    private fun createComponents(): Triple<AgentLayer, FlowLayer, EmitterManager> {
        val agents = AgentLayer(80, 40, AgentLayoutOrientation.CIRCULAR)
        val flow = FlowLayer(80, 40)
        val emitters = EmitterManager()
        return Triple(agents, flow, emitters)
    }

    private fun createTimeline(): Timeline {
        val (agents, flow, emitters) = createComponents()
        return WaveformDemoTimeline.build(agents, flow, emitters)
    }

    @Test
    fun `timeline builds successfully`() {
        val timeline = createTimeline()
        assertTrue(timeline.phaseCount > 0)
    }

    @Test
    fun `timeline has correct number of phases`() {
        val timeline = createTimeline()
        assertEquals(8, timeline.phaseCount)
    }

    @Test
    fun `timeline has correct total duration`() {
        val timeline = createTimeline()
        val expectedDuration = WaveformDemoTimeline.TOTAL_DURATION_SECONDS.seconds
        assertEquals(expectedDuration, timeline.totalDuration)
    }

    @Test
    fun `phase ordering matches expected sequence`() {
        val timeline = createTimeline()
        val phaseNames = timeline.phases.map { it.name }
        assertEquals(WaveformDemoTimeline.PHASE_NAMES, phaseNames)
    }

    @Test
    fun `all expected phases are present`() {
        val timeline = createTimeline()
        for (name in WaveformDemoTimeline.PHASE_NAMES) {
            assertNotNull(timeline.getPhase(name), "Missing phase: $name")
        }
    }

    @Test
    fun `silence phase has 2 second duration`() {
        val timeline = createTimeline()
        val silence = timeline.getPhase("silence")
        assertNotNull(silence)
        assertEquals(2.seconds, silence.duration)
    }

    @Test
    fun `execution phase has 3 second duration`() {
        val timeline = createTimeline()
        val execute = timeline.getPhase("execution")
        assertNotNull(execute)
        assertEquals(3.seconds, execute.duration)
    }

    @Test
    fun `planning phase has 3 second duration`() {
        val timeline = createTimeline()
        val plan = timeline.getPhase("planning")
        assertNotNull(plan)
        assertEquals(3.seconds, plan.duration)
    }

    @Test
    fun `spark-arrives phase spawns agent and fires effects`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)

        assertEquals(0, agents.agentCount, "No agents before timeline starts")
        assertEquals(0, emitters.activeCount, "No effects before timeline starts")

        // Fire the spark-arrives onStart
        timeline.phases[1].onStart?.invoke()

        assertEquals(1, agents.agentCount, "Spark should be added")
        assertNotNull(agents.getAgent("spark"))
        assertTrue(emitters.activeCount >= 2, "SparkBurst + HeightPulse effects should be active")
    }

    @Test
    fun `planning phase spawns second agent`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)

        // Fire spark-arrives to add first agent
        timeline.phases[1].onStart?.invoke()
        assertEquals(1, agents.agentCount)

        // Fire planning onStart (sets phase) then keyframe at 1s (adds Jazz)
        timeline.phases[3].onStart?.invoke()

        // Fire keyframe at 1.0s — Jazz spawns
        val planPhase = timeline.phases[3]
        val spawnKeyframe = planPhase.keyframes.find {
            it.time == 1000.milliseconds
        }
        assertNotNull(spawnKeyframe, "Should have keyframe at 1s for Jazz spawn")
        spawnKeyframe.action()

        assertEquals(2, agents.agentCount, "Jazz should be added")
        assertNotNull(agents.getAgent("jazz"))
    }

    @Test
    fun `delegation phase creates flow connection`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)

        // Set up agents first
        timeline.phases[1].onStart?.invoke()  // spark-arrives
        timeline.phases[3].onStart?.invoke()  // planning onStart
        timeline.phases[3].keyframes[0].action()  // Jazz spawns at 1s

        assertEquals(0, flow.connectionCount, "No connections before delegation")

        // Fire delegation onStart
        timeline.phases[4].onStart?.invoke()

        assertEquals(1, flow.connectionCount, "Connection should be created")
    }

    @Test
    fun `completion phase fires confetti and completes agents`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)

        // Set up agents
        timeline.phases[1].onStart?.invoke()
        timeline.phases[3].keyframes[0].action()

        val emitterCountBefore = emitters.activeCount

        // Fire completion onStart
        timeline.phases[6].onStart?.invoke()

        assertTrue(emitters.activeCount > emitterCountBefore, "Confetti effects should be active")
        assertEquals(AgentActivityState.COMPLETE, agents.getAgent("spark")?.state)
        assertEquals(AgentActivityState.COMPLETE, agents.getAgent("jazz")?.state)
    }

    @Test
    fun `reflection phase sets EVALUATE cognitive phase`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)

        // Set up agents
        timeline.phases[1].onStart?.invoke()
        timeline.phases[3].keyframes[0].action()

        // Fire reflection onStart
        timeline.phases[7].onStart?.invoke()

        assertEquals(CognitivePhase.EVALUATE, agents.getAgent("spark")?.cognitivePhase)
        assertEquals(CognitivePhase.EVALUATE, agents.getAgent("jazz")?.cognitivePhase)
    }

    @Test
    fun `full timeline plays through via controller without errors`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)
        val controller = TimelineController(timeline)

        controller.play()

        // Simulate stepping through the full timeline at ~30fps
        val stepMs = 33f
        val totalSteps = (WaveformDemoTimeline.TOTAL_DURATION_SECONDS * 1000 / stepMs).toInt() + 10

        for (i in 0..totalSteps) {
            if (controller.isCompleted) break
            controller.update(stepMs / 1000f)
        }

        assertTrue(controller.isCompleted, "Timeline should complete after full duration")
        assertEquals(2, agents.agentCount, "Both agents should be present")
    }

    @Test
    fun `execution phase keyframes fire effects at both agents`() {
        val (agents, flow, emitters) = createComponents()
        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)

        // Set up agents
        timeline.phases[1].onStart?.invoke()
        timeline.phases[3].keyframes[0].action()

        // Fire execution onStart
        timeline.phases[5].onStart?.invoke()

        val countAfterOnStart = emitters.activeCount

        // Fire the 0.5s keyframe (HeightPulse at both agents)
        val execPhase = timeline.phases[5]
        val pulseKeyframe = execPhase.keyframes.find {
            it.time == 500.milliseconds
        }
        assertNotNull(pulseKeyframe, "Should have keyframe at 0.5s")
        pulseKeyframe.action()

        assertTrue(
            emitters.activeCount > countAfterOnStart,
            "HeightPulse effects should fire at both agents"
        )
    }
}
