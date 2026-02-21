package link.socket.ampere.animation.render

import link.socket.ampere.animation.agent.CognitivePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CognitiveColorRampTest {

    @Test
    fun `darkest luminance returns first color stop`() {
        assertEquals(
            CognitiveColorRamp.PERCEIVE.colorStops.first(),
            CognitiveColorRamp.PERCEIVE.colorForLuminance(0.0f)
        )
    }

    @Test
    fun `brightest luminance returns last color stop`() {
        assertEquals(
            CognitiveColorRamp.PERCEIVE.colorStops.last(),
            CognitiveColorRamp.PERCEIVE.colorForLuminance(1.0f)
        )
    }

    @Test
    fun `mid luminance returns mid-ramp value`() {
        val ramp = CognitiveColorRamp.PERCEIVE
        val midColor = ramp.colorForLuminance(0.5f)
        // Should be somewhere in the middle, not first or last
        assertNotEquals(ramp.colorStops.first(), midColor)
        assertNotEquals(ramp.colorStops.last(), midColor)
    }

    @Test
    fun `luminance is clamped below zero`() {
        assertEquals(
            CognitiveColorRamp.EXECUTE.colorStops.first(),
            CognitiveColorRamp.EXECUTE.colorForLuminance(-1.0f)
        )
    }

    @Test
    fun `luminance is clamped above one`() {
        assertEquals(
            CognitiveColorRamp.EXECUTE.colorStops.last(),
            CognitiveColorRamp.EXECUTE.colorForLuminance(2.0f)
        )
    }

    @Test
    fun `all phase ramps have at least 5 color stops`() {
        val ramps = listOf(
            CognitiveColorRamp.PERCEIVE,
            CognitiveColorRamp.RECALL,
            CognitiveColorRamp.PLAN,
            CognitiveColorRamp.EXECUTE,
            CognitiveColorRamp.EVALUATE,
            CognitiveColorRamp.NEUTRAL
        )
        ramps.forEach { ramp ->
            assert(ramp.colorStops.size >= 5) {
                "${ramp.phase} ramp has only ${ramp.colorStops.size} stops, need at least 5"
            }
        }
    }

    @Test
    fun `forPhase returns correct ramp for each phase`() {
        assertEquals(CognitivePhase.PERCEIVE, CognitiveColorRamp.forPhase(CognitivePhase.PERCEIVE).phase)
        assertEquals(CognitivePhase.RECALL, CognitiveColorRamp.forPhase(CognitivePhase.RECALL).phase)
        assertEquals(CognitivePhase.PLAN, CognitiveColorRamp.forPhase(CognitivePhase.PLAN).phase)
        assertEquals(CognitivePhase.EXECUTE, CognitiveColorRamp.forPhase(CognitivePhase.EXECUTE).phase)
        assertEquals(CognitivePhase.EVALUATE, CognitiveColorRamp.forPhase(CognitivePhase.EVALUATE).phase)
    }

    @Test
    fun `forPhase handles LOOP and NONE`() {
        val loopRamp = CognitiveColorRamp.forPhase(CognitivePhase.LOOP)
        val noneRamp = CognitiveColorRamp.forPhase(CognitivePhase.NONE)
        assertEquals(loopRamp, noneRamp)
    }

    @Test
    fun `ramp with fewer than 2 stops throws`() {
        assertFailsWith<IllegalArgumentException> {
            CognitiveColorRamp(CognitivePhase.NONE, listOf(42))
        }
    }

    @Test
    fun `all color stops are valid ANSI 256 range`() {
        val allRamps = listOf(
            CognitiveColorRamp.PERCEIVE,
            CognitiveColorRamp.RECALL,
            CognitiveColorRamp.PLAN,
            CognitiveColorRamp.EXECUTE,
            CognitiveColorRamp.EVALUATE,
            CognitiveColorRamp.NEUTRAL
        )
        allRamps.forEach { ramp ->
            ramp.colorStops.forEach { color ->
                assert(color in 0..255) {
                    "${ramp.phase} has invalid color code $color"
                }
            }
        }
    }

    @Test
    fun `different phases produce different colors at same luminance`() {
        val perceiveColor = CognitiveColorRamp.PERCEIVE.colorForLuminance(0.5f)
        val executeColor = CognitiveColorRamp.EXECUTE.colorForLuminance(0.5f)
        assertNotEquals(perceiveColor, executeColor)
    }
}
