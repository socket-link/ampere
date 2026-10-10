package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals

/** J5: every [Confidence] level is self-reported, and the F10 mapping applies to those alone. */
class ConfidenceSourceTest {

    @Test
    fun `every confidence level is self-reported`() {
        Confidence.entries.forEach { level ->
            assertEquals(ConfidenceSource.SELF_REPORTED, level.source, "source of $level")
        }
    }

    @Test
    fun `the F10 mapping is a quarter a half and three quarters`() {
        assertEquals(0.25, Confidence.LOW.asProbability())
        assertEquals(0.5, Confidence.MEDIUM.asProbability())
        assertEquals(0.75, Confidence.HIGH.asProbability())
    }
}
