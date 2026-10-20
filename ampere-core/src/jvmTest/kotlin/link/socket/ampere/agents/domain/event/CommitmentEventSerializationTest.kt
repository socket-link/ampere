package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.data.DEFAULT_JSON

/**
 * AMPR-475 (F36): `CommitmentEvent.Undertaken` and `Released` through the serializer the event
 * store writes with, named on the wire by their serial names, and registered by type.
 */
class CommitmentEventSerializationTest {

    @Test
    fun `both events round-trip through the store's serializer`() {
        listOf<Event>(UNDERTAKEN, UNDERTAKEN.copy(runId = null), RELEASED, RELEASED.copy(runId = "run-1")).forEach {
            val text = DEFAULT_JSON.encodeToString(Event.serializer(), it)

            assertEquals(it, DEFAULT_JSON.decodeFromString(Event.serializer(), text))
        }
    }

    @Test
    fun `each event is named on the wire by its serial name`() {
        mapOf<Event, String>(
            UNDERTAKEN to "CommitmentEvent.Undertaken",
            RELEASED to "CommitmentEvent.Released",
        ).forEach { (event, name) ->
            val payload = Json.parseToJsonElement(DEFAULT_JSON.encodeToString(Event.serializer(), event)).jsonObject

            assertEquals(name, payload.getValue("type").jsonPrimitive.content)
            assertEquals(WORK_ID, payload.getValue("workId").jsonPrimitive.content)
        }
    }

    @Test
    fun `both event types are registered and parse by name`() {
        assertTrue(CommitmentEvent.Undertaken.EVENT_TYPE in EventRegistry.allEventTypes)
        assertTrue(CommitmentEvent.Released.EVENT_TYPE in EventRegistry.allEventTypes)
        assertEquals(CommitmentEvent.Undertaken.EVENT_TYPE, EventRegistry.parseEventType("commitmentundertaken"))
        assertEquals(CommitmentEvent.Released.EVENT_TYPE, EventRegistry.parseEventType("commitmentreleased"))
    }

    private companion object {
        const val WORK_ID = "work-session-1"
        val AT: Instant = Instant.parse("2026-10-20T09:00:00Z")

        val UNDERTAKEN = CommitmentEvent.Undertaken(
            eventId = "commitment-undertaken-1",
            committer = EventSource.Human.ID,
            workId = WORK_ID,
            due = Instant.parse("2026-10-20T11:00:00Z"),
            description = "Write the quarterly report",
            runId = "run-1",
            eventSource = EventSource.Human,
            timestamp = AT,
        )

        val RELEASED = CommitmentEvent.Released(
            eventId = "commitment-released-1",
            workId = WORK_ID,
            reason = "Done",
            eventSource = EventSource.Human,
            timestamp = AT,
        )
    }
}
