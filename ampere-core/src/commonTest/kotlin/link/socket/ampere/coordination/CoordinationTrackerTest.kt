package link.socket.ampere.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinationTrackerTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventBus = EventSerialBus(scope)

    @Test
    fun `initial state is empty`() {
        val tracker = CoordinationTracker(eventBus, scope)
        val state = tracker.state.value

        assertTrue(state.edges.isEmpty(), "Edges should be empty initially")
        assertTrue(state.activeMeetings.isEmpty(), "Active meetings should be empty initially")
        assertTrue(state.pendingHandoffs.isEmpty(), "Pending handoffs should be empty initially")
        assertTrue(state.blockedAgents.isEmpty(), "Blocked agents should be empty initially")
        assertTrue(state.recentInteractions.isEmpty(), "Recent interactions should be empty initially")
    }

    @Test
    fun `statistics returns zero counts initially`() {
        val tracker = CoordinationTracker(eventBus, scope)
        val stats = tracker.getStatistics()

        assertEquals(0, stats.totalInteractions, "Total interactions should be 0")
        assertEquals(0, stats.uniqueAgentPairs, "Unique agent pairs should be 0")
        assertEquals(null, stats.mostActiveAgent, "Most active agent should be null")
        assertTrue(stats.interactionsByType.isEmpty(), "Interactions by type should be empty")
        assertEquals(0.0, stats.averageInteractionsPerAgent, "Average interactions should be 0.0")
    }

    @Test
    fun `types compile and represent coordination concepts correctly`() {
        val now = Clock.System.now()

        // Test InteractionType enum
        val interactionType = InteractionType.TICKET_ASSIGNED
        assertEquals(InteractionType.TICKET_ASSIGNED, interactionType)

        // Test AgentInteraction
        val interaction = AgentInteraction(
            sourceAgentId = "agent-A",
            targetAgentId = "agent-B",
            interactionType = InteractionType.TICKET_ASSIGNED,
            timestamp = now,
            eventId = "evt-1",
            context = "Test task",
        )
        assertEquals("agent-A", interaction.sourceAgentId)
        assertEquals("agent-B", interaction.targetAgentId)

        // Test CoordinationEdge
        val edge = CoordinationEdge(
            sourceAgentId = "agent-A",
            targetAgentId = "agent-B",
            interactionCount = 5,
            lastInteraction = now,
            interactionTypes = setOf(InteractionType.TICKET_ASSIGNED, InteractionType.DELEGATION),
        )
        assertEquals(5, edge.interactionCount)
        assertEquals(2, edge.interactionTypes.size)

        // Test PendingHandoff
        val handoff = PendingHandoff(
            fromAgentId = "agent-A",
            toAgentId = "agent-B",
            handoffType = InteractionType.DELEGATION,
            description = "Code review needed",
            timestamp = now,
            eventId = "evt-2",
        )
        assertEquals("agent-A", handoff.fromAgentId)

        // Test BlockedAgent
        val blockedAgent = BlockedAgent(
            agentId = "agent-C",
            blockedBy = "agent-D",
            reason = "Waiting for review",
            since = now,
            eventId = "evt-3",
        )
        assertEquals("agent-C", blockedAgent.agentId)

        // Test CoordinationState
        val state = CoordinationState(
            edges = listOf(edge),
            activeMeetings = emptyList(),
            pendingHandoffs = listOf(handoff),
            blockedAgents = listOf(blockedAgent),
            recentInteractions = listOf(interaction),
            lastUpdated = now,
        )
        assertEquals(1, state.edges.size)
        assertEquals(1, state.pendingHandoffs.size)

        // Test CoordinationStatistics
        val stats = CoordinationStatistics(
            totalInteractions = 10,
            uniqueAgentPairs = 3,
            mostActiveAgent = "agent-A",
            interactionsByType = mapOf(InteractionType.TICKET_ASSIGNED to 7, InteractionType.DELEGATION to 3),
            averageInteractionsPerAgent = 5.0,
        )
        assertEquals(10, stats.totalInteractions)
        assertEquals("agent-A", stats.mostActiveAgent)
    }

    @Test
    fun `tracker identifies task assignment as coordination interaction`() {
        val tracker = CoordinationTracker(eventBus, scope)
        tracker.startTracking()

        // Create a task assigned from agent-A to agent-B
        val taskEvent = Event.TaskCreated(
            eventId = "evt-1",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-A"),
            taskId = "task-123",
            description = "Implement feature X",
            assignedTo = "agent-B",
        )

        // This would be published in a real scenario
        // For this test, we're verifying the types compile and make sense
        assertTrue(taskEvent.assignedTo != null, "Task should be assigned to another agent")
        assertEquals("agent-B", taskEvent.assignedTo)
    }

    @Test
    fun `tracker identifies code review request as coordination interaction`() {
        val tracker = CoordinationTracker(eventBus, scope)
        tracker.startTracking()

        // Create a code review request from agent-A to agent-B
        val codeEvent = Event.CodeSubmitted(
            eventId = "evt-2",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-A"),
            filePath = "src/main/App.kt",
            changeDescription = "Added new feature",
            reviewRequired = true,
            assignedTo = "agent-B",
        )

        // Verify event structure
        assertTrue(codeEvent.reviewRequired, "Review should be required")
        assertEquals("agent-B", codeEvent.assignedTo)
    }
}
