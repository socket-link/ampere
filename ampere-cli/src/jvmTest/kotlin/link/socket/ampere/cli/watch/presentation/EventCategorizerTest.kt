package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for EventCategorizer to ensure events are properly classified
 * by significance level.
 */
class EventCategorizerTest {

    @Test
    fun `CRITICAL - QuestionRaised events are critical`() {
        val event = Event.QuestionRaised(
            eventId = "evt-1",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.HIGH,
            questionText = "Need help with this decision",
            context = "Blocked on implementation choice"
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.CRITICAL, significance)
    }

    @Test
    fun `CRITICAL - TicketBlocked events are critical`() {
        val event = TicketEvent.TicketBlocked(
            eventId = "evt-2",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.HIGH,
            ticketId = "TICK-123",
            blockingReason = "Waiting for external dependency"
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.CRITICAL, significance)
    }

    @Test
    fun `CRITICAL - EscalationRequested events are critical`() {
        val event = MessageEvent.EscalationRequested(
            eventId = "evt-3",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.HIGH,
            threadId = "thread-1",
            reason = "Need human input on critical decision"
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.CRITICAL, significance)
    }

    @Test
    fun `SIGNIFICANT - TaskCreated events are significant`() {
        val event = Event.TaskCreated(
            eventId = "evt-4",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.MEDIUM,
            taskId = "TASK-456",
            description = "Implement new feature",
            assignedTo = "agent-dev"
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.SIGNIFICANT, significance)
    }

    @Test
    fun `SIGNIFICANT - CodeSubmitted events are significant`() {
        val event = Event.CodeSubmitted(
            eventId = "evt-5",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-dev"),
            urgency = Urgency.LOW,
            filePath = "src/main/Auth.kt",
            changeDescription = "Add OAuth support",
            reviewRequired = true,
            assignedTo = "agent-reviewer"
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.SIGNIFICANT, significance)
    }

    @Test
    fun `SIGNIFICANT - TicketCreated events are significant`() {
        val event = TicketEvent.TicketCreated(
            eventId = "evt-6",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-pm"),
            urgency = Urgency.MEDIUM,
            ticketId = "TICK-789",
            title = "Fix login bug",
            description = "Users cannot login with OAuth",
            ticketType = TicketType.BUG,
            priority = TicketPriority.HIGH
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.SIGNIFICANT, significance)
    }

    @Test
    fun `SIGNIFICANT - TicketStatusChanged events are significant`() {
        val event = TicketEvent.TicketStatusChanged(
            eventId = "evt-7",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-dev"),
            urgency = Urgency.LOW,
            ticketId = "TICK-789",
            previousStatus = TicketStatus.InProgress,
            newStatus = TicketStatus.Done
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.SIGNIFICANT, significance)
    }

    @Test
    fun `ROUTINE - KnowledgeRecalled events are routine`() {
        val event = MemoryEvent.KnowledgeRecalled(
            eventId = "evt-8",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.LOW,
            context = MemoryContext(
                taskType = "authentication",
                tags = setOf("security", "oauth"),
                description = "authentication patterns"
            ),
            resultsFound = 5,
            averageRelevance = 0.85,
            topKnowledgeIds = listOf("k1", "k2", "k3", "k4", "k5")
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.ROUTINE, significance)
    }

    @Test
    fun `ROUTINE - KnowledgeStored events are routine`() {
        val event = MemoryEvent.KnowledgeStored(
            eventId = "evt-9",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.LOW,
            knowledgeId = "k-123",
            knowledgeType = KnowledgeType.FROM_OUTCOME,
            taskType = "architecture",
            tags = listOf("authentication", "security"),
            approach = "Chose OAuth2 for authentication"
        )

        val significance = EventCategorizer.categorize(event)
        assertEquals(EventSignificance.ROUTINE, significance)
    }

    @Test
    fun `ROUTINE events should not display by default`() {
        val routineEvent = MemoryEvent.KnowledgeRecalled(
            eventId = "evt-10",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.LOW,
            context = MemoryContext(
                taskType = "test",
                tags = emptySet(),
                description = "test query"
            ),
            resultsFound = 0,
            averageRelevance = 0.0,
            topKnowledgeIds = emptyList()
        )

        val significance = EventCategorizer.categorize(routineEvent)
        assertEquals(EventSignificance.ROUTINE, significance)
        assertEquals(false, significance.shouldDisplayByDefault)
    }

    @Test
    fun `SIGNIFICANT events should display by default`() {
        val significantEvent = Event.TaskCreated(
            eventId = "evt-11",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.MEDIUM,
            taskId = "TASK-999",
            description = "Test task",
            assignedTo = null
        )

        val significance = EventCategorizer.categorize(significantEvent)
        assertEquals(EventSignificance.SIGNIFICANT, significance)
        assertEquals(true, significance.shouldDisplayByDefault)
    }

    @Test
    fun `CRITICAL events should display by default`() {
        val criticalEvent = Event.QuestionRaised(
            eventId = "evt-12",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-test"),
            urgency = Urgency.HIGH,
            questionText = "Critical question",
            context = "Emergency"
        )

        val significance = EventCategorizer.categorize(criticalEvent)
        assertEquals(EventSignificance.CRITICAL, significance)
        assertEquals(true, significance.shouldDisplayByDefault)
    }
}
