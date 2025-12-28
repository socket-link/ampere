package link.socket.ampere.agents.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource

class EventSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val stubTimestamp = Clock.System.now()
    private val stubEventSource = EventSource.Agent("agent-X")

    @Test
    fun `serialize and deserialize task created event as concrete`() {
        val original = Event.TaskCreated(
            eventId = "11111111-1111-1111-1111-111111111111",
            urgency = Urgency.LOW,
            timestamp = stubTimestamp,
            eventSource = stubEventSource,
            taskId = "T-001",
            description = "Implement event bus",
            assignedTo = "agent-B",
        )
        val text = json.encodeToString(original)
        val decoded = json.decodeFromString<Event.TaskCreated>(text)
        assertIs<Event.TaskCreated>(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `serialize and deserialize question raised event polymorphic`() {
        val original: Event = Event.QuestionRaised(
            eventId = "22222222-2222-2222-2222-222222222222",
            timestamp = stubTimestamp,
            eventSource = stubEventSource,
            questionText = "What database should we use?",
            context = "KMP persistence options",
            urgency = Urgency.HIGH,
        )
        val text = json.encodeToString(Event.serializer(), original)
        val decoded = json.decodeFromString(Event.serializer(), text)
        assertIs<Event.QuestionRaised>(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `serialize and deserialize code submitted event as concrete`() {
        val original = Event.CodeSubmitted(
            eventId = "33333333-3333-3333-3333-333333333333",
            urgency = Urgency.MEDIUM,
            timestamp = stubTimestamp,
            eventSource = stubEventSource,
            filePath = "shared/src/commonMain/kotlin/EventBus.kt",
            changeDescription = "Initial commit",
            reviewRequired = true,
            assignedTo = null,
        )
        val text = json.encodeToString(original)
        val decoded = json.decodeFromString<Event.CodeSubmitted>(text)
        assertIs<Event.CodeSubmitted>(decoded)
        assertEquals(original, decoded)
    }
}
