package link.socket.ampere.plug.spi

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.event.CanonPerceivedEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonPerson
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.canon.adapter.ReadableCanonAdapter
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.observe.environmentRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.subject.subjectRef

/**
 * [recordedPerceive] (F7): the perceive event is minted before the enumeration, stamped onto every
 * entity at projection, and stored through the one door before the page reaches its caller — so a
 * perceived entity's Observation resolves to a recorded event.
 */
class RecordedPerceiveTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle

    private val linkId = LinkId("contacts-link")
    private val observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "scout", scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    private suspend fun perceivedEvents(): List<CanonPerceivedEvent> =
        handle.repository.getAllEvents().getOrThrow().filterIsInstance<CanonPerceivedEvent>()

    @Test
    fun `a perceived entity round-trips to an observation whose provenance is the recorded perceive event`() =
        runBlocking<Unit> {
            val source = ContactsSource(names = listOf("ada", "grace"))

            val perception = source.recordedPerceive(PerceiveQuery(linkId = linkId), handle.api).getOrThrow()

            val event = perceivedEvents().single()
            assertEquals(perception.perceiveEventId, event.eventId)
            assertEquals(linkId, event.linkId)
            assertEquals(listOf("ada", "grace"), event.canonIds)
            assertEquals(EventSource.Agent("scout"), event.eventSource)
            assertEquals(perception.perceiveEventId, source.lastQuery?.perceiveEventId)

            val holder = event.eventSource.subjectRef()
            val observations = perception.observations(holder)

            assertEquals(2, observations.size)
            observations.zip(perception.page.entities).forEach { (belief, person) ->
                assertEquals(event.eventId, person.provenance.perceiveEventId)
                assertEquals(event.eventId, belief.provenance.createdBy)
                assertEquals(perception.recordedAt, belief.provenance.createdAt)
                assertEquals(holder, belief.holder)
                assertEquals(person.environmentRef, belief.subject)
                assertEquals(Sensitivity.PROTECTED, belief.sensitivity)
            }
        }

    @Test
    fun `an empty page is still a recorded perceive`() = runBlocking<Unit> {
        val perception = ContactsSource(names = emptyList())
            .recordedPerceive(PerceiveQuery(linkId = linkId), handle.api)
            .getOrThrow()

        assertEquals(perception.perceiveEventId, perceivedEvents().single().eventId)
        assertTrue(perceivedEvents().single().canonIds.isEmpty())
    }

    @Test
    fun `a source that does not stamp the page publishes nothing and fails loudly`() = runBlocking<Unit> {
        val failure = ContactsSource(names = listOf("ada"), stamp = false)
            .recordedPerceive(PerceiveQuery(linkId = linkId), handle.api)
            .exceptionOrNull()

        val unstamped = assertIs<UnstampedPerceiveException>(failure)
        assertEquals(listOf(CanonId("ada")), unstamped.canonIds)
        assertTrue(perceivedEvents().isEmpty(), "an unaccounted page must not be recorded as perceived")
    }

    @Test
    fun `a source failure publishes nothing`() = runBlocking<Unit> {
        val boom = IllegalStateException("provider down")
        val source = object : PerceiveSource<CanonPerson> {
            override val emits = setOf(CanonType.PERSON)
            override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<CanonPerson>> =
                Result.failure(boom)
        }

        val failure = source.recordedPerceive(PerceiveQuery(linkId = linkId), handle.api).exceptionOrNull()

        assertEquals(boom, failure)
        assertTrue(perceivedEvents().isEmpty())
    }

    @Test
    fun `the perceive event decodes back from the store`() = runBlocking<Unit> {
        ContactsSource(names = listOf("ada")).recordedPerceive(PerceiveQuery(linkId = linkId), handle.api).getOrThrow()

        val stored = handle.repository.getAllEvents().getOrThrow().filterIsInstance<CanonPerceivedEvent>().single()

        assertEquals(CanonPerceivedEvent.EVENT_TYPE, stored.eventType)
        assertEquals(listOf("ada"), stored.canonIds)
    }

    /** Projects a contact per name; stamps the query's perceive event id unless told not to. */
    private inner class ContactsSource(
        private val names: List<String>,
        private val stamp: Boolean = true,
    ) : PerceiveSource<CanonPerson> {

        var lastQuery: PerceiveQuery? = null

        override val emits: Set<CanonType> = setOf(CanonType.PERSON)

        override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<CanonPerson>> {
            lastQuery = query
            val entities = names.map { name ->
                ContactAdapter.project(
                    payload = NativePayload(CONTACT_SCHEMA, JsonObject(mapOf("name" to JsonPrimitive(name)))),
                    handle = SourceHandle(query.linkId, sourceSystem = "apple.contacts", nativeId = name),
                    observedAt = observedAt,
                    perceiveEventId = if (stamp) query.perceiveEventId else null,
                ).getOrThrow()
            }
            return Result.success(PerceivePage.unfiltered(query, entities))
        }
    }

    private object ContactAdapter : ReadableCanonAdapter<CanonPerson>() {
        override val canonType: CanonType = CanonType.PERSON
        override val nativeSchema: NativeSchema = CONTACT_SCHEMA

        override fun projectFields(fields: JsonObject, provenance: CanonProvenance): Result<CanonPerson> =
            Result.success(
                CanonPerson(
                    canonId = CanonId(provenance.sourceHandle.nativeId),
                    provenance = provenance,
                    displayName = fields.getValue("name").jsonPrimitive.content,
                ),
            )

        override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
            Result.failure(UnsupportedOperationException("read-only fixture"))
    }

    private companion object {
        val CONTACT_SCHEMA = NativeSchema("ContactEntity")
    }
}
