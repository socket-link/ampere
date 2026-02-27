package link.socket.ampere.compose

import link.socket.ampere.animation.flow.FlowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowCanvasRendererTest {

    // --- colorForFlowState ---

    @Test
    fun colorForFlowState_dormant_returns_flowDormant() {
        assertEquals(CognitivePalette.flowDormant, FlowCanvasRenderer.colorForFlowState(FlowState.DORMANT))
    }

    @Test
    fun colorForFlowState_activating_returns_flowActive() {
        assertEquals(CognitivePalette.flowActive, FlowCanvasRenderer.colorForFlowState(FlowState.ACTIVATING))
    }

    @Test
    fun colorForFlowState_transmitting_returns_flowActive() {
        assertEquals(CognitivePalette.flowActive, FlowCanvasRenderer.colorForFlowState(FlowState.TRANSMITTING))
    }

    @Test
    fun colorForFlowState_received_returns_agentComplete() {
        assertEquals(CognitivePalette.agentComplete, FlowCanvasRenderer.colorForFlowState(FlowState.RECEIVED))
    }

    // --- alphaForFlowState ---

    @Test
    fun alphaForFlowState_dormant_returns_015() {
        assertEquals(0.15f, FlowCanvasRenderer.alphaForFlowState(FlowState.DORMANT))
    }

    @Test
    fun alphaForFlowState_activating_returns_04() {
        assertEquals(0.4f, FlowCanvasRenderer.alphaForFlowState(FlowState.ACTIVATING))
    }

    @Test
    fun alphaForFlowState_transmitting_returns_07() {
        assertEquals(0.7f, FlowCanvasRenderer.alphaForFlowState(FlowState.TRANSMITTING))
    }

    @Test
    fun alphaForFlowState_received_returns_05() {
        assertEquals(0.5f, FlowCanvasRenderer.alphaForFlowState(FlowState.RECEIVED))
    }

    @Test
    fun alphaForFlowState_transmitting_is_highest() {
        val transmitting = FlowCanvasRenderer.alphaForFlowState(FlowState.TRANSMITTING)
        for (state in FlowState.entries) {
            if (state != FlowState.TRANSMITTING) {
                assertTrue(
                    transmitting > FlowCanvasRenderer.alphaForFlowState(state),
                    "TRANSMITTING alpha should be higher than $state",
                )
            }
        }
    }

    @Test
    fun alphaForFlowState_all_values_in_valid_range() {
        for (state in FlowState.entries) {
            val alpha = FlowCanvasRenderer.alphaForFlowState(state)
            assertTrue(alpha in 0f..1f, "$state alpha $alpha should be in [0, 1]")
        }
    }
}
