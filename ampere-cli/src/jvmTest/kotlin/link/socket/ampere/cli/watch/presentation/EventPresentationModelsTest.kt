package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EventPresentationModelsTest {

    // ========== EventSignificance Tests ==========

    @Test
    fun `EventSignificance CRITICAL should display by default`() {
        assertTrue(EventSignificance.CRITICAL.shouldDisplayByDefault)
    }

    @Test
    fun `EventSignificance SIGNIFICANT should display by default`() {
        assertTrue(EventSignificance.SIGNIFICANT.shouldDisplayByDefault)
    }

    @Test
    fun `EventSignificance ROUTINE should not display by default`() {
        assertFalse(EventSignificance.ROUTINE.shouldDisplayByDefault)
    }

    @Test
    fun `EventSignificance enum has expected number of values`() {
        assertEquals(3, EventSignificance.entries.size)
    }

    // ========== CognitiveCluster Tests ==========

    @Test
    fun `CognitiveCluster calculates duration from event timestamps`() {
        val startTime = Clock.System.now()
        val events = listOf(
            createTaskEvent(timestamp = startTime),
            createTaskEvent(timestamp = startTime + 100.milliseconds),
            createTaskEvent(timestamp = startTime + 250.milliseconds)
        )

        val cluster = CognitiveCluster(
            agentId = "agent-test",
            startTimestamp = startTime,
            events = events,
            cycleType = CognitiveClusterType.TASK_PROCESSING
        )

        assertEquals(250, cluster.durationMillis)
    }

    @Test
    fun `CognitiveCluster with single event has zero duration`() {
        val timestamp = Clock.System.now()
        val events = listOf(createTaskEvent(timestamp = timestamp))

        val cluster = CognitiveCluster(
            agentId = "agent-test",
            startTimestamp = timestamp,
            events = events,
            cycleType = CognitiveClusterType.KNOWLEDGE_RECALL_STORE
        )

        assertEquals(0, cluster.durationMillis)
    }

    @Test
    fun `CognitiveCluster with empty events list has zero duration`() {
        val cluster = CognitiveCluster(
            agentId = "agent-test",
            startTimestamp = Clock.System.now(),
            events = emptyList(),
            cycleType = CognitiveClusterType.MEETING_PARTICIPATION
        )

        assertEquals(0, cluster.durationMillis)
    }

    @Test
    fun `CognitiveCluster correctly stores all properties`() {
        val agentId = "agent-orchestrator"
        val startTime = Clock.System.now()
        val events = listOf(createTaskEvent(), createQuestionEvent())
        val cycleType = CognitiveClusterType.TASK_PROCESSING

        val cluster = CognitiveCluster(
            agentId = agentId,
            startTimestamp = startTime,
            events = events,
            cycleType = cycleType
        )

        assertEquals(agentId, cluster.agentId)
        assertEquals(startTime, cluster.startTimestamp)
        assertEquals(events, cluster.events)
        assertEquals(cycleType, cluster.cycleType)
    }

    @Test
    fun `CognitiveClusterType enum has expected values`() {
        val types = CognitiveClusterType.entries
        assertTrue(types.contains(CognitiveClusterType.KNOWLEDGE_RECALL_STORE))
        assertTrue(types.contains(CognitiveClusterType.TASK_PROCESSING))
        assertTrue(types.contains(CognitiveClusterType.MEETING_PARTICIPATION))
        assertEquals(3, types.size)
    }

    // ========== AgentActivityState Tests ==========

    @Test
    fun `AgentActivityState correctly stores all properties`() {
        val agentId = "agent-dev-001"
        val displayName = "dev-001"
        val currentState = AgentState.WORKING
        val timestamp = Clock.System.now()
        val cycles = 5
        val isIdle = false

        val activityState = AgentActivityState(
            agentId = agentId,
            displayName = displayName,
            currentState = currentState,
            lastActivityTimestamp = timestamp,
            consecutiveCognitiveCycles = cycles,
            isIdle = isIdle
        )

        assertEquals(agentId, activityState.agentId)
        assertEquals(displayName, activityState.displayName)
        assertEquals(currentState, activityState.currentState)
        assertEquals(timestamp, activityState.lastActivityTimestamp)
        assertEquals(cycles, activityState.consecutiveCognitiveCycles)
        assertEquals(isIdle, activityState.isIdle)
    }

    @Test
    fun `AgentActivityState with high consecutive cycles indicates idle state`() {
        val activityState = AgentActivityState(
            agentId = "agent-idle",
            displayName = "idle",
            currentState = AgentState.IDLE,
            lastActivityTimestamp = Clock.System.now() - 60.seconds,
            consecutiveCognitiveCycles = 100,
            isIdle = true
        )

        assertTrue(activityState.isIdle)
        assertTrue(activityState.consecutiveCognitiveCycles > 50)
    }

    @Test
    fun `AgentActivityState with zero consecutive cycles indicates active state`() {
        val activityState = AgentActivityState(
            agentId = "agent-active",
            displayName = "active",
            currentState = AgentState.WORKING,
            lastActivityTimestamp = Clock.System.now(),
            consecutiveCognitiveCycles = 0,
            isIdle = false
        )

        assertFalse(activityState.isIdle)
        assertEquals(0, activityState.consecutiveCognitiveCycles)
    }

    @Test
    fun `AgentState enum has expected values and display text`() {
        assertEquals("thinking", AgentState.THINKING.displayText)
        assertEquals("working", AgentState.WORKING.displayText)
        assertEquals("idle", AgentState.IDLE.displayText)
        assertEquals("in meeting", AgentState.IN_MEETING.displayText)
        assertEquals("waiting", AgentState.WAITING.displayText)
        assertEquals(5, AgentState.entries.size)
    }

    @Test
    fun `AgentState display text is lowercase`() {
        AgentState.entries.forEach { state ->
            assertTrue(state.displayText.all { it.isLowerCase() || it.isWhitespace() })
        }
    }

    // ========== SignificantEventSummary Tests ==========

    @Test
    fun `SignificantEventSummary correctly stores all properties`() {
        val eventId = "evt-123"
        val timestamp = Clock.System.now()
        val eventType = "TaskCreated"
        val sourceAgentName = "orchestrator"
        val summaryText = "Created task TASK-456"
        val significance = EventSignificance.SIGNIFICANT

        val summary = SignificantEventSummary(
            eventId = eventId,
            timestamp = timestamp,
            eventType = eventType,
            sourceAgentName = sourceAgentName,
            summaryText = summaryText,
            significance = significance
        )

        assertEquals(eventId, summary.eventId)
        assertEquals(timestamp, summary.timestamp)
        assertEquals(eventType, summary.eventType)
        assertEquals(sourceAgentName, summary.sourceAgentName)
        assertEquals(summaryText, summary.summaryText)
        assertEquals(significance, summary.significance)
    }

    @Test
    fun `SignificantEventSummary can be created for critical events`() {
        val summary = SignificantEventSummary(
            eventId = "evt-critical-1",
            timestamp = Clock.System.now(),
            eventType = "HumanEscalation",
            sourceAgentName = "agent-blocked",
            summaryText = "Escalated to human: Decision needed",
            significance = EventSignificance.CRITICAL
        )

        assertEquals(EventSignificance.CRITICAL, summary.significance)
        assertTrue(summary.significance.shouldDisplayByDefault)
    }

    @Test
    fun `SignificantEventSummary can be created for routine events`() {
        val summary = SignificantEventSummary(
            eventId = "evt-routine-1",
            timestamp = Clock.System.now(),
            eventType = "KnowledgeRecall",
            sourceAgentName = "agent-researcher",
            summaryText = "Retrieved knowledge from memory",
            significance = EventSignificance.ROUTINE
        )

        assertEquals(EventSignificance.ROUTINE, summary.significance)
        assertFalse(summary.significance.shouldDisplayByDefault)
    }

    // ========== SystemVitals Tests ==========

    @Test
    fun `SystemVitals has correct default values`() {
        val vitals = SystemVitals()

        assertEquals(0, vitals.activeAgentCount)
        assertEquals(SystemState.IDLE, vitals.systemState)
        assertEquals(null, vitals.lastSignificantEventTime)
    }

    @Test
    fun `SystemVitals correctly stores custom values`() {
        val timestamp = Clock.System.now()
        val vitals = SystemVitals(
            activeAgentCount = 5,
            systemState = SystemState.WORKING,
            lastSignificantEventTime = timestamp
        )

        assertEquals(5, vitals.activeAgentCount)
        assertEquals(SystemState.WORKING, vitals.systemState)
        assertEquals(timestamp, vitals.lastSignificantEventTime)
    }

    @Test
    fun `SystemVitals can represent attention needed state`() {
        val vitals = SystemVitals(
            activeAgentCount = 3,
            systemState = SystemState.ATTENTION_NEEDED,
            lastSignificantEventTime = Clock.System.now()
        )

        assertEquals(SystemState.ATTENTION_NEEDED, vitals.systemState)
    }

    @Test
    fun `SystemState enum has expected values`() {
        val states = SystemState.entries
        assertTrue(states.contains(SystemState.IDLE))
        assertTrue(states.contains(SystemState.WORKING))
        assertTrue(states.contains(SystemState.ATTENTION_NEEDED))
        assertEquals(3, states.size)
    }

    // ========== WatchViewState Tests ==========

    @Test
    fun `WatchViewState correctly composes presentation models`() {
        val vitals = SystemVitals(activeAgentCount = 2, systemState = SystemState.WORKING)
        val agentStates = mapOf(
            "agent-1" to AgentActivityState(
                agentId = "agent-1",
                displayName = "agent-1",
                currentState = AgentState.WORKING,
                lastActivityTimestamp = Clock.System.now(),
                consecutiveCognitiveCycles = 0,
                isIdle = false
            )
        )
        val events = listOf(
            SignificantEventSummary(
                eventId = "evt-1",
                timestamp = Clock.System.now(),
                eventType = "TaskCreated",
                sourceAgentName = "agent-1",
                summaryText = "Created task",
                significance = EventSignificance.SIGNIFICANT
            )
        )

        val viewState = WatchViewState(
            systemVitals = vitals,
            agentStates = agentStates,
            recentSignificantEvents = events
        )

        assertEquals(vitals, viewState.systemVitals)
        assertEquals(agentStates, viewState.agentStates)
        assertEquals(events, viewState.recentSignificantEvents)
    }

    @Test
    fun `WatchViewState can represent empty system state`() {
        val viewState = WatchViewState(
            systemVitals = SystemVitals(),
            agentStates = emptyMap(),
            recentSignificantEvents = emptyList()
        )

        assertEquals(0, viewState.systemVitals.activeAgentCount)
        assertTrue(viewState.agentStates.isEmpty())
        assertTrue(viewState.recentSignificantEvents.isEmpty())
    }

    // ========== Helper Functions ==========

    private fun createTaskEvent(
        eventId: String = "evt-task-${Clock.System.now().toEpochMilliseconds()}",
        timestamp: Instant = Clock.System.now(),
        source: EventSource = EventSource.Agent("agent-test"),
        urgency: Urgency = Urgency.MEDIUM
    ): Event.TaskCreated = Event.TaskCreated(
        eventId = eventId,
        timestamp = timestamp,
        eventSource = source,
        urgency = urgency,
        taskId = "TASK-123",
        description = "Test task",
        assignedTo = "agent-worker"
    )

    private fun createQuestionEvent(
        eventId: String = "evt-question-${Clock.System.now().toEpochMilliseconds()}",
        timestamp: Instant = Clock.System.now(),
        source: EventSource = EventSource.Agent("agent-test"),
        urgency: Urgency = Urgency.MEDIUM
    ): Event.QuestionRaised = Event.QuestionRaised(
        eventId = eventId,
        timestamp = timestamp,
        eventSource = source,
        urgency = urgency,
        questionText = "Test question?",
        context = "Test context"
    )
}
