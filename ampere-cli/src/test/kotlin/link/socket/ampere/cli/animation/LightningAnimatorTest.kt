package link.socket.ampere.cli.animation

import link.socket.ampere.cli.animation.LightningAnimator.Glow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightningAnimatorTest {

    @Test
    fun `test discharge sequences are non-empty`() {
        assertTrue(LightningAnimator.DISCHARGE_SEQUENCE.isNotEmpty())
        assertTrue(LightningAnimator.COMPACT_SEQUENCE.isNotEmpty())
        assertTrue(LightningAnimator.STANDARD_SPINNER.isNotEmpty())
        assertTrue(LightningAnimator.LIGHTNING_PULSE.isNotEmpty())
    }

    @Test
    fun `test all frames have positive duration`() {
        LightningAnimator.DISCHARGE_SEQUENCE.forEach { frame ->
            assertTrue(frame.durationMs > 0, "Frame ${frame.symbol} should have positive duration")
        }
        LightningAnimator.COMPACT_SEQUENCE.forEach { frame ->
            assertTrue(frame.durationMs > 0, "Frame ${frame.symbol} should have positive duration")
        }
        LightningAnimator.STANDARD_SPINNER.forEach { frame ->
            assertTrue(frame.durationMs > 0, "Frame ${frame.symbol} should have positive duration")
        }
        LightningAnimator.LIGHTNING_PULSE.forEach { frame ->
            assertTrue(frame.durationMs > 0, "Frame ${frame.symbol} should have positive duration")
        }
    }

    @Test
    fun `test standard spinner sequence matches spec`() {
        val expectedSymbols = listOf("◐", "◓", "◑", "◒")
        val expectedDurations = listOf(150L, 150L, 150L, 150L)
        val sequence = LightningAnimator.STANDARD_SPINNER
        assertEquals(expectedSymbols, sequence.map { it.symbol })
        assertEquals(expectedDurations, sequence.map { it.durationMs })
        assertEquals(600L, sequence.sumOf { it.durationMs })
    }

    @Test
    fun `test lightning pulse sequence matches spec`() {
        val expectedSymbols = listOf("·", "˙", "⁝", "⚡", "✧", "·")
        val expectedDurations = listOf(120L, 80L, 70L, 100L, 80L, 150L)
        val sequence = LightningAnimator.LIGHTNING_PULSE
        assertEquals(expectedSymbols, sequence.map { it.symbol })
        assertEquals(expectedDurations, sequence.map { it.durationMs })
        assertEquals(600L, sequence.sumOf { it.durationMs })
    }

    @Test
    fun `test corona pattern with center at position 2`() {
        val pattern = LightningAnimator.coronaPattern(5, 2)
        assertEquals(
            listOf(Glow.NORMAL, Glow.BRIGHT, Glow.FLASH, Glow.BRIGHT, Glow.NORMAL),
            pattern
        )
    }

    @Test
    fun `test corona pattern with center at position 0`() {
        val pattern = LightningAnimator.coronaPattern(5, 0)
        assertEquals(Glow.FLASH, pattern[0])
        assertEquals(Glow.BRIGHT, pattern[1])
        assertEquals(Glow.NORMAL, pattern[2])
    }

    @Test
    fun `test corona pattern with center at last position`() {
        val pattern = LightningAnimator.coronaPattern(5, 4)
        assertEquals(Glow.NORMAL, pattern[2])
        assertEquals(Glow.BRIGHT, pattern[3])
        assertEquals(Glow.FLASH, pattern[4])
    }

    @Test
    fun `test bouncing corona position cycles correctly`() {
        val textLength = 5
        // First half of cycle (0 -> 4)
        assertEquals(0, LightningAnimator.bouncingCoronaPosition(textLength, 0))
        assertEquals(1, LightningAnimator.bouncingCoronaPosition(textLength, 1))
        assertEquals(2, LightningAnimator.bouncingCoronaPosition(textLength, 2))
        assertEquals(3, LightningAnimator.bouncingCoronaPosition(textLength, 3))
        assertEquals(4, LightningAnimator.bouncingCoronaPosition(textLength, 4))
        // Second half of cycle (4 -> 0)
        assertEquals(3, LightningAnimator.bouncingCoronaPosition(textLength, 5))
        assertEquals(2, LightningAnimator.bouncingCoronaPosition(textLength, 6))
        assertEquals(1, LightningAnimator.bouncingCoronaPosition(textLength, 7))
        assertEquals(0, LightningAnimator.bouncingCoronaPosition(textLength, 8))
        // Cycle repeats
        assertEquals(1, LightningAnimator.bouncingCoronaPosition(textLength, 9))
    }

    @Test
    fun `test bouncing corona position with single character`() {
        assertEquals(0, LightningAnimator.bouncingCoronaPosition(1, 0))
        assertEquals(0, LightningAnimator.bouncingCoronaPosition(1, 5))
        assertEquals(0, LightningAnimator.bouncingCoronaPosition(1, 100))
    }

    @Test
    fun `test apply corona generates ANSI formatted string`() {
        val result = LightningAnimator.applyCorona("ABC", 1)
        // Should contain ANSI codes and the text
        assertTrue(result.contains("A"))
        assertTrue(result.contains("B"))
        assertTrue(result.contains("C"))
        assertTrue(result.contains("\u001b[")) // ANSI escape sequence
        assertTrue(result.endsWith("\u001b[0m")) // Should end with RESET
    }

    @Test
    fun `test glow enum converts to ANSI codes`() {
        // Just verify they all produce valid ANSI codes
        Glow.entries.forEach { glow ->
            val ansi = glow.toAnsi()
            assertTrue(ansi.contains("\u001b["), "Glow $glow should produce ANSI code")
        }
    }
}
