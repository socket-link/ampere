package link.socket.ampere.agents.events.relay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.Urgency

class EventFiltersTest {

    private val stubSourceA = EventSource.Agent("agent-A")
    private val stubSourceB = EventSource.Agent("agent-B")
    private val stubSourceC = EventSource.Agent("agent-C")
    private val stubSourceD = EventSource.Agent("agent-D")
    private val stubSourceHuman = EventSource.Human

    private fun taskEvent(
        eventId: String = "evt-1",
        source: EventSource = stubSourceA,
        urgency: Urgency = Urgency.HIGH
    ): Event.TaskCreated = Event.TaskCreated(
        eventId = eventId,
        urgency = urgency,
        timestamp = Clock.System.now(),
        eventSource = source,
        taskId = "task-123",
        description = "Do something important",
        assignedTo = "agent-B",
    )

    private fun questionEvent(
        eventId: String = "evt-2",
        source: EventSource = stubSourceC,
        urgency: Urgency = Urgency.MEDIUM
    ): Event.QuestionRaised = Event.QuestionRaised(
        eventId = eventId,
        timestamp = Clock.System.now(),
        eventSource = source,
        questionText = "Why?",
        context = "Testing context",
        urgency = urgency,
    )

    private fun codeSubmittedEvent(
        eventId: String = "evt-3",
        source: EventSource = stubSourceD,
        urgency: Urgency = Urgency.LOW
    ): Event.CodeSubmitted = Event.CodeSubmitted(
        eventId = eventId,
        timestamp = Clock.System.now(),
        eventSource = source,
        filePath = "/path/to/test.kt",
        changeDescription = "Added test function",
        reviewRequired = true,
        assignedTo = "agent-reviewer",
        urgency = urgency,
    )

    @Test
    fun `empty filter matches all events`() {
        val filter = EventRelayFilters()

        assertTrue(filter.isEmpty())
        assertTrue(filter.matches(taskEvent()))
        assertTrue(filter.matches(questionEvent()))
        assertTrue(filter.matches(codeSubmittedEvent()))
    }

    @Test
    fun `filter by event type matches correctly`() {
        val filter = EventRelayFilters(
            eventTypes = setOf(Event.TaskCreated.EVENT_TYPE)
        )

        assertFalse(filter.isEmpty())
        assertTrue(filter.matches(taskEvent()))
        assertFalse(filter.matches(questionEvent()))
        assertFalse(filter.matches(codeSubmittedEvent()))
    }

    @Test
    fun `filter by multiple event types uses OR logic`() {
        val filter = EventRelayFilters(
            eventTypes = setOf(
                Event.TaskCreated.EVENT_TYPE,
                Event.QuestionRaised.EVENT_TYPE
            )
        )

        assertTrue(filter.matches(taskEvent()))
        assertTrue(filter.matches(questionEvent()))
        assertFalse(filter.matches(codeSubmittedEvent()))
    }

    @Test
    fun `filter by source ID matches correctly`() {
        val filter = EventRelayFilters(
            eventSources = setOf(stubSourceA),
        )

        assertTrue(filter.matches(taskEvent(source = stubSourceA)))
        assertFalse(filter.matches(taskEvent(source = stubSourceB)))
        assertFalse(filter.matches(questionEvent(source = stubSourceC)))
    }

    @Test
    fun `filter by multiple source IDs uses OR logic`() {
        val filter = EventRelayFilters(
            eventSources = setOf(stubSourceA, stubSourceC),
        )

        assertTrue(filter.matches(taskEvent(source = stubSourceA)))
        assertFalse(filter.matches(taskEvent(source = stubSourceB)))
        assertTrue(filter.matches(questionEvent(source = stubSourceC)))
    }

    @Test
    fun `filter by urgency level matches correctly`() {
        val filter = EventRelayFilters(
            urgencies = setOf(Urgency.HIGH),
        )

        assertTrue(filter.matches(taskEvent(urgency = Urgency.HIGH)))
        assertFalse(filter.matches(taskEvent(urgency = Urgency.MEDIUM)))
        assertFalse(filter.matches(taskEvent(urgency = Urgency.LOW)))
    }

    @Test
    fun `filter by multiple urgency levels uses OR logic`() {
        val filter = EventRelayFilters(
            urgencies = setOf(Urgency.HIGH, Urgency.MEDIUM)
        )

        assertTrue(filter.matches(taskEvent(urgency = Urgency.HIGH)))
        assertTrue(filter.matches(questionEvent(urgency = Urgency.MEDIUM)))
        assertFalse(filter.matches(codeSubmittedEvent(urgency = Urgency.LOW)))
    }

    @Test
    fun `filter by event ID matches correctly`() {
        val filter = EventRelayFilters(
            eventIds = setOf("evt-1")
        )

        assertTrue(filter.matches(taskEvent(eventId = "evt-1")))
        assertFalse(filter.matches(taskEvent(eventId = "evt-2")))
    }

    @Test
    fun `combining multiple filters uses AND logic`() {
        val filter = EventRelayFilters(
            eventTypes = setOf(Event.TaskCreated.EVENT_TYPE),
            eventSources = setOf(stubSourceA),
            urgencies = setOf(Urgency.HIGH)
        )

        // Matches all criteria
        assertTrue(filter.matches(
            taskEvent(source = stubSourceA, urgency = Urgency.HIGH)
        ))

        // Wrong event type
        assertFalse(filter.matches(
            questionEvent(source = stubSourceA, urgency = Urgency.HIGH)
        ))

        // Wrong source
        assertFalse(filter.matches(
            taskEvent(source = stubSourceB, urgency = Urgency.HIGH)
        ))

        // Wrong urgency
        assertFalse(filter.matches(
            taskEvent(source = stubSourceA, urgency = Urgency.LOW)
        ))
    }

    @Test
    fun `factory method forEventType creates correct filter`() {
        val filter = EventRelayFilters.forEventType(Event.TaskCreated.EVENT_TYPE)

        assertTrue(filter.matches(taskEvent()))
        assertFalse(filter.matches(questionEvent()))
    }

    @Test
    fun `factory method forSource creates correct filter`() {
        val filter = EventRelayFilters.forSource(stubSourceA)

        assertTrue(filter.matches(taskEvent(source = stubSourceA)))
        assertFalse(filter.matches(taskEvent(source = stubSourceB)))
    }

    @Test
    fun `factory method forUrgency creates correct filter`() {
        val filter = EventRelayFilters.forUrgency(Urgency.HIGH)

        assertTrue(filter.matches(taskEvent(urgency = Urgency.HIGH)))
        assertFalse(filter.matches(taskEvent(urgency = Urgency.MEDIUM)))
    }

    @Test
    fun `NONE constant matches all events`() {
        val filter = EventRelayFilters.NONE

        assertTrue(filter.isEmpty())
        assertTrue(filter.matches(taskEvent()))
        assertTrue(filter.matches(questionEvent()))
        assertTrue(filter.matches(codeSubmittedEvent()))
    }

    @Test
    fun `filter works with human event source`() {
        val humanEvent = Event.TaskCreated(
            eventId = "evt-human",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Human,
            taskId = "task-456",
            description = "Human-created task",
            assignedTo = "agent-X",
        )

        val filter = EventRelayFilters(eventSources = setOf(stubSourceHuman))

        assertTrue(filter.matches(humanEvent))
        assertFalse(filter.matches(taskEvent(source = stubSourceA)))
    }
}
