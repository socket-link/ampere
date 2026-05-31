package link.socket.ampere.cli.render

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LumosBridgeControllerTest {

    private val largeTickSeconds = 5.0f
    private val maxTransitionTicks = 8

    private fun newScene(): CognitiveSceneRuntime = CognitiveSceneRuntime(
        SceneConfiguration(
            width = 8,
            height = 8,
            enableWaveform = false,
            enableParticles = false,
            enableFlow = false,
            enableEmitters = false,
            enableCamera = false,
            enableAtmosphere = true,
            initialAtmosphere = AtmospherePresets.IDLE,
        ),
    )

    @Test
    fun `tick is a no-op when the scene provider returns null`() = runBlocking {
        val scope = TestScope(UnconfinedTestDispatcher())
        val bus = EventSerialBus(scope)
        val controller = LumosBridgeController(bus = bus, sceneProvider = { null })

        controller.tick()
        assertFalse(controller.isStarted, "bridge must not start without a scene")
    }

    @Test
    fun `tick lazy-starts the bridge once the scene becomes available`() = runBlocking {
        val scope = TestScope(UnconfinedTestDispatcher())
        val bus = EventSerialBus(scope)
        var scene: CognitiveSceneRuntime? = null
        val controller = LumosBridgeController(bus = bus, sceneProvider = { scene })

        controller.tick()
        assertFalse(controller.isStarted)

        scene = newScene()
        controller.tick()
        assertTrue(controller.isStarted, "bridge must start once scene is available")
    }

    @Test
    fun `phase event drives the runtime to the strategy's atmosphere`() = runBlocking {
        val scope = TestScope(UnconfinedTestDispatcher())
        val bus = EventSerialBus(scope)
        val scene = newScene()
        val controller = LumosBridgeController(bus = bus, sceneProvider = { scene })

        controller.tick() // start the bridge
        assertTrue(controller.isStarted)

        bus.publish(phaseEntered(CognitivePhase.EXECUTE))
        completeInFlightTransition(scene, controller)

        assertEquals(AtmospherePresets.READY, scene.currentAtmosphere)
    }

    @Test
    fun `stop is idempotent`() = runBlocking {
        val scope = TestScope(UnconfinedTestDispatcher())
        val bus = EventSerialBus(scope)
        val controller = LumosBridgeController(bus = bus, sceneProvider = { newScene() })

        controller.tick()
        controller.stop()
        controller.stop() // must not throw

        assertFalse(controller.isStarted)
    }

    private suspend fun completeInFlightTransition(
        scene: CognitiveSceneRuntime,
        controller: LumosBridgeController,
    ) {
        repeat(maxTransitionTicks) {
            val choreographer = scene.atmosphereChoreographer ?: return
            if (choreographer.activeTransition == null) {
                controller.tick()
                return
            }
            scene.update(largeTickSeconds)
        }
        controller.tick()
    }

    private fun phaseEntered(phase: CognitivePhase): CognitivePhaseEvent.PhaseEntered =
        CognitivePhaseEvent.PhaseEntered(
            eventId = "evt-phase-${phase.name}",
            timestamp = Instant.fromEpochSeconds(0),
            eventSource = EventSource.Agent("agent-A"),
            agentId = "agent-A",
            oldPhase = null,
            newPhase = phase,
            nestingDepth = 0,
        )
}
