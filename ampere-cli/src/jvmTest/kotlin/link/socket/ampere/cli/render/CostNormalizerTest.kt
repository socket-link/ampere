package link.socket.ampere.cli.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CostNormalizerTest {
    @Test
    fun `cost normalization uses broad logarithmic bands`() {
        val haikuCost = CostNormalizer.normalizeCost(0.0001)
        val sonnetCost = CostNormalizer.normalizeCost(0.01)
        val opusCost = CostNormalizer.normalizeCost(0.1)

        assertTrue(haikuCost in 0.05f..0.2f, "Expected low-cost call to stay subtle, got $haikuCost")
        assertTrue(sonnetCost in 0.45f..0.7f, "Expected mid-cost call near the middle, got $sonnetCost")
        assertTrue(opusCost in 0.75f..0.95f, "Expected high-cost call to read as hot, got $opusCost")
    }

    @Test
    fun `latency normalization spans interactive to slow calls`() {
        val fast = CostNormalizer.normalizeLatency(100)
        val medium = CostNormalizer.normalizeLatency(1_000)
        val slow = CostNormalizer.normalizeLatency(10_000)

        assertTrue(fast in 0.1f..0.2f, "Expected fast call to stay low intensity, got $fast")
        assertTrue(medium in 0.4f..0.6f, "Expected medium call near midpoint, got $medium")
        assertTrue(slow in 0.85f..0.95f, "Expected slow call to read as intense, got $slow")
    }

    @Test
    fun `token normalization distinguishes sparse and dense calls`() {
        val sparse = CostNormalizer.normalizeTokens(100)
        val medium = CostNormalizer.normalizeTokens(5_000)
        val dense = CostNormalizer.normalizeTokens(100_000)

        assertTrue(sparse in 0.02f..0.1f, "Expected sparse call to remain faint, got $sparse")
        assertTrue(medium in 0.4f..0.6f, "Expected medium call near midpoint, got $medium")
        assertTrue(dense in 0.85f..0.95f, "Expected dense call to saturate strongly, got $dense")
    }

    @Test
    fun `zero and extreme values stay clamped`() {
        assertEquals(0f, CostNormalizer.normalizeCost(0.0))
        assertEquals(0f, CostNormalizer.normalizeLatency(0))
        assertEquals(0f, CostNormalizer.normalizeTokens(0))

        assertEquals(1f, CostNormalizer.normalizeCost(10.0))
        assertEquals(1f, CostNormalizer.normalizeLatency(60_000))
        assertEquals(1f, CostNormalizer.normalizeTokens(500_000))
    }
}
