package link.socket.ampere

import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.emitter.CognitiveEmitterBridge
import link.socket.ampere.animation.emitter.EmitterManager
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.timeline.TimelineController
import link.socket.ampere.animation.timeline.TimelineEvent
import link.socket.ampere.animation.timeline.WaveformDemoTimeline
import link.socket.ampere.cli.render.WaveformPaneRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the waveform demo pipeline — verifying that the timeline,
 * renderer, and emitter components integrate correctly for the demo command.
 */
class DemoCommandTest {

    private fun createDemoPipeline(): DemoPipeline {
        val width = 60
        val height = 30
        val agents = AgentLayer(width, height, AgentLayoutOrientation.CIRCULAR)
        val flow = FlowLayer(width, height)
        val emitters = EmitterManager()
        val emitterBridge = CognitiveEmitterBridge(emitters)
        val substrate = SubstrateState.create(width, height, baseDensity = 0.2f)

        val waveformPane = WaveformPaneRenderer(
            agentLayer = agents,
            emitterManager = emitters,
            cognitiveEmitterBridge = emitterBridge
        )

        val timeline = WaveformDemoTimeline.build(agents, flow, emitters)
        val controller = TimelineController(timeline)

        return DemoPipeline(
            agents = agents,
            flow = flow,
            emitters = emitters,
            substrate = substrate,
            waveformPane = waveformPane,
            controller = controller,
            width = width,
            height = height
        )
    }

    data class DemoPipeline(
        val agents: AgentLayer,
        val flow: FlowLayer,
        val emitters: EmitterManager,
        val substrate: SubstrateState,
        val waveformPane: WaveformPaneRenderer,
        val controller: TimelineController,
        val width: Int,
        val height: Int
    )

    @Test
    fun `demo pipeline renders initial frame without errors`() {
        val pipeline = createDemoPipeline()

        pipeline.waveformPane.update(pipeline.substrate, pipeline.flow, 0.033f)
        val lines = pipeline.waveformPane.render(pipeline.width, pipeline.height)

        assertEquals(pipeline.height, lines.size, "Should produce exactly height lines")
    }

    @Test
    fun `demo runs through full timeline rendering each frame`() {
        val pipeline = createDemoPipeline()
        val dt = 0.05f // 20 FPS
        var frameCount = 0

        pipeline.controller.play()

        val totalSteps = (WaveformDemoTimeline.TOTAL_DURATION_SECONDS / dt).toInt() + 10

        for (i in 0..totalSteps) {
            if (pipeline.controller.isCompleted) break

            pipeline.controller.update(dt)
            pipeline.waveformPane.update(pipeline.substrate, pipeline.flow, dt)

            val lines = pipeline.waveformPane.render(pipeline.width, pipeline.height)
            assertEquals(pipeline.height, lines.size, "Frame $frameCount: Should have correct height")
            frameCount++
        }

        assertTrue(pipeline.controller.isCompleted, "Timeline should complete")
        assertTrue(frameCount > 50, "Should render many frames (got $frameCount)")
    }

    @Test
    fun `demo populates agents during playback`() {
        val pipeline = createDemoPipeline()

        assertEquals(0, pipeline.agents.agentCount, "No agents before start")

        pipeline.controller.play()

        // Advance past spark-arrives phase (starts at 2s)
        advanceTimeline(pipeline, 3.0f)

        assertTrue(pipeline.agents.agentCount >= 1, "At least Spark should be present")
        assertNotNull(pipeline.agents.getAgent("spark"))
    }

    @Test
    fun `demo creates flow connections during delegation phase`() {
        val pipeline = createDemoPipeline()

        pipeline.controller.play()

        // Advance past delegation phase start (silence=2 + spark=2 + memory=2 + planning=3 = 9s)
        advanceTimeline(pipeline, 10.0f)

        assertTrue(pipeline.flow.connectionCount >= 1, "Should have flow connection")
    }

    @Test
    fun `demo fires emitter effects during playback`() {
        val pipeline = createDemoPipeline()
        var effectsFired = false

        pipeline.controller.play()

        // Advance to spark-arrives where effects fire
        val dt = 0.05f
        val stepsToSparkArrives = (2.5f / dt).toInt()

        for (i in 0..stepsToSparkArrives) {
            pipeline.controller.update(dt)
            pipeline.emitters.update(dt)
            if (pipeline.emitters.activeCount > 0) {
                effectsFired = true
            }
        }

        assertTrue(effectsFired, "Emitter effects should fire during spark-arrives phase")
    }

    @Test
    fun `demo timeline tracks phase transitions`() {
        val pipeline = createDemoPipeline()
        val phaseNames = mutableListOf<String>()

        pipeline.controller.addEventListener { event ->
            when (event) {
                is TimelineEvent.PhaseStarted -> phaseNames.add(event.phase.name)
                else -> {}
            }
        }

        pipeline.controller.play()
        advanceTimeline(pipeline, WaveformDemoTimeline.TOTAL_DURATION_SECONDS.toFloat() + 1f)

        assertEquals(
            WaveformDemoTimeline.PHASE_NAMES,
            phaseNames,
            "All phases should fire in order"
        )
    }

    @Test
    fun `rendered lines have correct width`() {
        val pipeline = createDemoPipeline()

        pipeline.controller.play()
        advanceTimeline(pipeline, 5.0f) // Middle of the demo

        pipeline.waveformPane.update(pipeline.substrate, pipeline.flow, 0.033f)
        val lines = pipeline.waveformPane.render(pipeline.width, pipeline.height)

        for ((i, line) in lines.withIndex()) {
            // Strip ANSI codes to check visible width
            val visible = line.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            assertEquals(
                pipeline.width,
                visible.length,
                "Line $i visible width should match requested width"
            )
        }
    }

    private fun advanceTimeline(pipeline: DemoPipeline, seconds: Float) {
        val dt = 0.05f
        val steps = (seconds / dt).toInt()
        for (i in 0..steps) {
            if (pipeline.controller.isCompleted) break
            pipeline.controller.update(dt)
            pipeline.emitters.update(dt)
        }
    }
}
