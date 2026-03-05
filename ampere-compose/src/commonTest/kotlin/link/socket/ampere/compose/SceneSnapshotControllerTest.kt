package link.socket.ampere.compose

import link.socket.phosphor.signal.CognitivePhase
import kotlin.test.Test
import kotlin.test.assertTrue

class SceneSnapshotControllerTest {

    @Test
    fun `default controller seeds agents and scene data`() {
        val controller = SceneSnapshotController()

        val snapshot = controller.snapshot()

        assertTrue(snapshot.agentStates.isNotEmpty(), "Expected seeded agents in snapshot")
        assertTrue(snapshot.substrateState.width > 0, "Expected substrate field in snapshot")
        assertTrue(
            snapshot.waveformHeightField?.isNotEmpty() == true,
            "Expected waveform data in snapshot"
        )
    }

    @Test
    fun `update advances frame index and rotates phases`() {
        val controller = SceneSnapshotController(phaseStepSeconds = 0.05f)
        val initial = controller.snapshot()
        val initialById = initial.agentStates.associateBy { it.id }

        var updated = initial
        repeat(3) {
            updated = controller.update(0.05f)
        }

        assertTrue(updated.frameIndex > initial.frameIndex, "Expected frame index to advance")
        val changedPhase = updated.agentStates.any { state ->
            val previous = initialById[state.id]?.cognitivePhase
            previous != null && previous != state.cognitivePhase
        }
        assertTrue(changedPhase, "Expected at least one agent to rotate cognitive phase")
        assertTrue(updated.choreographyPhase != CognitivePhase.NONE, "Expected active choreography phase")
    }
}
