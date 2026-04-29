package link.socket.ampere.pause

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelAvailabilityTest {

    @Test
    fun `stub returns empty channel list on every platform`() {
        val availability = ChannelAvailability()
        assertEquals(emptyList(), availability.available())
        assertTrue(availability.available().isEmpty())
    }
}
