package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `every Ring 1 type has an Apple binding`() {
        CanonType.inRing(CanonRing.INTERCHANGE).forEach { type ->
            assertTrue(
                type.binding.apple != null,
                "${type.wireName} is Ring 1 but has no Apple binding — Ring 1 means it maps " +
                    "to an assistant-schema entity or a system value type",
            )
        }
    }

    @Test
    fun `no Ring 3 type claims an Apple binding`() {
        CanonType.inRing(CanonRing.SERVICE).forEach { type ->
            assertNull(
                type.binding.apple,
                "${type.wireName} is Ring 3 (service) but claims an Apple binding",
            )
        }
    }

    @Test
    fun `Ring 1 entity-schema bindings only name domains the SDK actually ships`() {
        // Enumerated from iPhoneOS 26.5 AppIntents.swiftinterface; see
        // .context/recon/apple-assistant-schemas-ios265.tsv
        val shippedDomains = setOf(
            "assistant", "books", "browser", "camera", "files", "journal", "mail",
            "photos", "presentation", "reader", "spreadsheet", "system",
            "visualIntelligence", "whiteboard", "wordProcessor",
        )

        CanonType.entries
            .mapNotNull { it.binding.apple as? AppleSchemaBinding.EntitySchema }
            .forEach { binding ->
                assertTrue(
                    binding.domain in shippedDomains,
                    "${binding.qualifiedName} names a domain that does not exist in the SDK",
                )
            }
    }

    @Test
    fun `DocumentKind domains are all real Apple document domains`() {
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
        // These were proposed as Ring 1 and failed the no-contortion rule: the
        // shipped Apple catalog has no calendar, reminders, clock, music/video,
        // messages, or notes domain. Re-promoting one without a new SDK pass is
        // the regression this test exists to catch.
        assertEquals(CanonRing.PLATFORM, CanonType.CALENDAR_EVENT.ring)
        assertEquals(CanonRing.PLATFORM, CanonType.REMINDER.ring)
        assertEquals(CanonRing.PLATFORM, CanonType.ALARM.ring)
        assertEquals(CanonRing.PLATFORM, CanonType.MEDIA_ITEM.ring)
        assertEquals(CanonRing.SERVICE, CanonType.MESSAGE.ring)
        assertEquals(CanonRing.SERVICE, CanonType.NOTE.ring)
    }

    @Test
    fun `Person and Place bind through system value types, not entity schemas`() {
        val person = CanonType.PERSON.binding.apple
        val place = CanonType.PLACE.binding.apple

        assertIs<AppleSchemaBinding.SystemValueType>(person)
        assertIs<AppleSchemaBinding.SystemValueType>(place)

        assertEquals("IntentPerson", person.identifier)
        assertEquals("CLPlacemark", place.identifier)
    }

    @Test
    fun `every lossy binding names at least one dropped field`() {
        CanonType.entries
            .filter { it.binding.apple != null }
            .forEach { type ->
                assertTrue(
                    type.binding.lossyFields.isNotEmpty(),
                    "${type.wireName} has an Apple binding but declares no lossy fields — " +
                        "a projection with nothing to preserve is almost certainly under-documented",
                )
            }
    }

    @Test
    fun `Android bindings are all still marked unverified`() {
        // androidx.appfunctions is not on this repo's classpath, so
        // AppFunctionSchemaDefinition could not be enumerated during recon.
        // When it lands, this test flips to assert real bindings.
        CanonType.entries.forEach { type ->
            assertTrue(
                type.binding.android is AndroidSchemaBinding.PendingSdkVerification ||
                    type.binding.android is AndroidSchemaBinding.None,
                "${type.wireName} claims a verified Android binding without an SDK pass",
            )
        }
    }

    @Test
    fun `qualified name renders the dotted Apple address`() {
        val mail = CanonType.EMAIL_MESSAGE.binding.apple
        assertIs<AppleSchemaBinding.EntitySchema>(mail)
        assertEquals("mail.message", mail.qualifiedName)
        assertEquals("MailMessageEntity", mail.identifier)
    }
}
