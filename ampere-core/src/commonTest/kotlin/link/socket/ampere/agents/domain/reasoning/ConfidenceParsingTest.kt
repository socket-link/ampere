package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfidenceParsingTest {

    @Test
    fun `parseOrNull recognises lowercase tokens`() {
        assertEquals(Confidence.LOW, Confidence.parseOrNull("low"))
        assertEquals(Confidence.MEDIUM, Confidence.parseOrNull("medium"))
        assertEquals(Confidence.HIGH, Confidence.parseOrNull("high"))
    }

    @Test
    fun `parseOrNull is case-insensitive`() {
        assertEquals(Confidence.HIGH, Confidence.parseOrNull("HIGH"))
        assertEquals(Confidence.HIGH, Confidence.parseOrNull("High"))
        assertEquals(Confidence.MEDIUM, Confidence.parseOrNull("MEDIUM"))
        assertEquals(Confidence.LOW, Confidence.parseOrNull("Low"))
    }

    @Test
    fun `parseOrNull trims whitespace`() {
        assertEquals(Confidence.HIGH, Confidence.parseOrNull("  high  "))
        assertEquals(Confidence.LOW, Confidence.parseOrNull("\tlow\n"))
    }

    @Test
    fun `parseOrNull returns null for unknown tokens`() {
        assertNull(Confidence.parseOrNull("unknown"))
        assertNull(Confidence.parseOrNull(""))
        assertNull(Confidence.parseOrNull("very-high"))
    }

    @Test
    fun `parseOrDefault returns MEDIUM for null`() {
        assertEquals(Confidence.MEDIUM, Confidence.parseOrDefault(null))
    }

    @Test
    fun `parseOrDefault returns MEDIUM for unknown strings`() {
        assertEquals(Confidence.MEDIUM, Confidence.parseOrDefault("nonsense"))
    }

    @Test
    fun `parseOrDefault honours an explicit default`() {
        assertEquals(Confidence.HIGH, Confidence.parseOrDefault(null, default = Confidence.HIGH))
        assertEquals(Confidence.LOW, Confidence.parseOrDefault("nonsense", default = Confidence.LOW))
    }

    @Test
    fun `parseOrDefault still parses known values`() {
        assertEquals(Confidence.HIGH, Confidence.parseOrDefault("high"))
        assertEquals(Confidence.LOW, Confidence.parseOrDefault("low", default = Confidence.HIGH))
    }
}
