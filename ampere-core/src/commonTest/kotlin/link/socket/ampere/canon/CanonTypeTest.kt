package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonTypeTest {

    @Test
    fun `wire names are unique across the whole canon`() {
        val wireNames = CanonType.entries.map { it.wireName }

        assertEquals(
            wireNames.size,
            wireNames.toSet().size,
            "Duplicate wireName found; wire names are a serialization contract",
        )
    }

    @Test
    fun `fromWireName round-trips every canon type`() {
        CanonType.entries.forEach { type ->
            assertEquals(type, CanonType.fromWireName(type.wireName))
        }
    }

    @Test
    fun `fromWireName returns null for an unknown name rather than throwing`() {
        assertNull(CanonType.fromWireName("definitely_not_a_canon_type"))
    }

    @Test
    fun `DocumentKind domains are all real Apple document domains`() {
        // AMPR-257: DocumentKind.appleDomain is a documented, narrow exception —
        // see the KDoc on DocumentKind in CanonRing1Entities.kt.
        val documentDomains = setOf(
            "files",
            "wordProcessor",
            "spreadsheet",
            "presentation",
            "reader",
            "whiteboard",
        )

        DocumentKind.entries.forEach { kind ->
            assertTrue(kind.appleDomain in documentDomains, "${kind.name} -> ${kind.appleDomain}")
        }
    }

    @Test
    fun `the six recon demotions stay demoted`() {
        // These were proposed as Ring 1 and failed the no-contortion rule — see
        // AppleCanonBindingRegistry (ampere-bindings-apple) for why. Re-promoting
        // one without a new SDK pass is the regression this test exists to catch.
        assertEquals(CanonRing.PLATFORM, CanonType.CALENDAR_EVENT.ring)
        assertEquals(CanonRing.PLATFORM, CanonType.REMINDER.ring)
        assertEquals(CanonRing.PLATFORM, CanonType.ALARM.ring)
        assertEquals(CanonRing.PLATFORM, CanonType.MEDIA_ITEM.ring)
        assertEquals(CanonRing.SERVICE, CanonType.MESSAGE.ring)
        assertEquals(CanonRing.SERVICE, CanonType.NOTE.ring)
    }
}
