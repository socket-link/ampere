package link.socket.ampere.bindings.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.canon.CanonRing
import link.socket.ampere.canon.CanonType

class AppleCanonBindingRegistryTest {

    @Test
    fun `every Ring 1 type has an Apple binding`() {
        CanonType.inRing(CanonRing.INTERCHANGE).forEach { type ->
            assertTrue(
                AppleCanonBindingRegistry.bindingFor(type).schema != null,
                "${type.wireName} is Ring 1 but has no Apple binding — Ring 1 means it maps " +
                    "to an assistant-schema entity or a system value type",
            )
        }
    }

    @Test
    fun `no Ring 3 type claims an Apple binding`() {
        CanonType.inRing(CanonRing.SERVICE).forEach { type ->
            assertNull(
                AppleCanonBindingRegistry.bindingFor(type).schema,
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
            .mapNotNull { AppleCanonBindingRegistry.bindingFor(it).schema as? AppleSchemaBinding.EntitySchema }
            .forEach { binding ->
                assertTrue(
                    binding.domain in shippedDomains,
                    "${binding.qualifiedName} names a domain that does not exist in the SDK",
                )
            }
    }

    @Test
    fun `the six recon demotions stay unbound on Apple`() {
        // These were proposed as Ring 1 and failed the no-contortion rule: the
        // shipped Apple catalog has no calendar, reminders, clock, music/video,
        // messages, or notes domain. Re-binding one without a new SDK pass is
        // the regression this test exists to catch.
        listOf(
            CanonType.CALENDAR_EVENT,
            CanonType.REMINDER,
            CanonType.ALARM,
            CanonType.MEDIA_ITEM,
            CanonType.MESSAGE,
            CanonType.NOTE,
        ).forEach { type ->
            assertNull(AppleCanonBindingRegistry.bindingFor(type).schema, "${type.wireName} should be unbound")
        }
    }

    @Test
    fun `Person and Place bind through system value types rather than entity schemas`() {
        val person = AppleCanonBindingRegistry.bindingFor(CanonType.PERSON).schema
        val place = AppleCanonBindingRegistry.bindingFor(CanonType.PLACE).schema

        assertIs<AppleSchemaBinding.SystemValueType>(person)
        assertIs<AppleSchemaBinding.SystemValueType>(place)

        assertEquals("IntentPerson", person.identifier)
        assertEquals("CLPlacemark", place.identifier)
    }

    @Test
    fun `every lossy binding names at least one dropped field`() {
        CanonType.entries
            .filter { AppleCanonBindingRegistry.bindingFor(it).schema != null }
            .forEach { type ->
                assertTrue(
                    AppleCanonBindingRegistry.bindingFor(type).lossyFields.isNotEmpty(),
                    "${type.wireName} has an Apple binding but declares no lossy fields — " +
                        "a projection with nothing to preserve is almost certainly under-documented",
                )
            }
    }

    @Test
    fun `qualified name renders the dotted Apple address`() {
        val mail = AppleCanonBindingRegistry.bindingFor(CanonType.EMAIL_MESSAGE).schema
        assertIs<AppleSchemaBinding.EntitySchema>(mail)
        assertEquals("mail.message", mail.qualifiedName)
        assertEquals("MailMessageEntity", mail.identifier)
    }
}
