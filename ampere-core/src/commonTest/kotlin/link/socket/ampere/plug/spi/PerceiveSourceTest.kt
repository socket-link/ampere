package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonPerson
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.canon.adapter.CanonConversionFailure
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest

class PerceiveSourceTest {

    private val linkId = LinkId("google-oauth-1")

    private fun provenance(nativeId: String) = CanonProvenance(
        sourceHandle = SourceHandle(linkId = linkId, sourceSystem = "apple.contacts", nativeId = nativeId),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    /** A canon-bearing source: emits PERSON, T is a real CanonEntity. */
    private class ContactsSource(
        private val page: PerceivePage<CanonPerson>,
    ) : PerceiveSource<CanonPerson> {
        override val emits: Set<CanonType> = setOf(CanonType.PERSON)
        override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<CanonPerson>> =
            Result.success(page)
    }

    data class NotificationPayload(val title: String, val body: String)

    /** A canon-external source: emits nothing, T has no CanonEntity base. */
    private class NotificationSource(
        private val page: PerceivePage<NotificationPayload>,
    ) : PerceiveSource<NotificationPayload> {
        override val emits: Set<CanonType> = emptySet()
        override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<NotificationPayload>> =
            Result.success(page)
    }

    @Test
    fun `a canon-bearing source and a canon-external source compile against the same interface`() = runTest {
        val person = CanonPerson(
            canonId = CanonId("person-1"),
            provenance = provenance("person-1"),
            displayName = "Ada Lovelace",
        )
        val contacts: PerceiveSource<CanonPerson> = ContactsSource(PerceivePage(entities = listOf(person)))
        val notifications: PerceiveSource<NotificationPayload> =
            NotificationSource(PerceivePage(entities = listOf(NotificationPayload("Reminder", "Standup in 5"))))

        val query = PerceiveQuery(linkId = linkId)

        assertEquals(listOf(person), contacts.perceive(query).getOrThrow().entities)
        assertEquals(setOf(CanonType.PERSON), contacts.emits)
        assertTrue(notifications.emits.isEmpty())
        assertEquals("Reminder", notifications.perceive(query).getOrThrow().entities.single().title)
    }

    @Test
    fun `a page with partial failures round-trips both lists`() = runTest {
        val ok = CanonPerson(
            canonId = CanonId("person-1"),
            provenance = provenance("person-1"),
            displayName = "Ada Lovelace",
        )
        val failures = listOf(
            CanonConversionFailure.MalformedField(
                canonType = CanonType.PERSON,
                field = "displayName",
                reason = "empty",
            ),
        )
        val source = ContactsSource(PerceivePage(entities = listOf(ok), partialFailures = failures))

        val result = source.perceive(PerceiveQuery(linkId = linkId)).getOrThrow()

        assertEquals(listOf(ok), result.entities)
        assertEquals(failures, result.partialFailures)
    }

    @Test
    fun `emits disagreeing with a manifest is detectable by a caller`() {
        val manifest = PlugManifest(
            id = PlugId("contacts-plug"),
            name = "Contacts",
            version = "1.0.0",
            emits = setOf(CanonType.PERSON, CanonType.EMAIL_MESSAGE),
        )
        val source: PerceiveSource<CanonPerson> = ContactsSource(PerceivePage(entities = emptyList()))

        assertTrue(source.emits != manifest.emits)
        assertEquals(setOf(CanonType.EMAIL_MESSAGE), manifest.emits - source.emits)
    }
}
