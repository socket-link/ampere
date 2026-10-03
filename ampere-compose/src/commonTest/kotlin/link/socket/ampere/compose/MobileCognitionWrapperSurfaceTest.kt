package link.socket.ampere.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileCognitionWrapperSurfaceTest {

    @Test
    fun `frameLabelText formats frame index and choreography phase`() {
        val controller = SceneSnapshotController()
        val snapshot = controller.snapshot()

        val label = frameLabelText(snapshot)

        assertEquals("Frame ${snapshot.frameIndex} • ${snapshot.choreographyPhase.name}", label)
    }

    @Test
    fun `frameLabelText tracks frame index as the controller advances`() {
        val controller = SceneSnapshotController(phaseStepSeconds = 0.05f)
        val updated = controller.update(0.05f)

        val label = frameLabelText(updated)

        assertTrue(
            label.startsWith("Frame ${updated.frameIndex} "),
            "Expected label to report the current frame index"
        )
    }
}
