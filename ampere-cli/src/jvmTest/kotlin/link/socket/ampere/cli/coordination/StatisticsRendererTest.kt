package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.status.MeetingStatus
import link.socket.ampere.coordination.AgentInteraction
import link.socket.ampere.coordination.CoordinationEdge
import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.CoordinationStatistics
import link.socket.ampere.coordination.InteractionType

class StatisticsRendererTest {

    private val renderer = StatisticsRenderer()

    @Test
    fun `renders summary statistics section`() {
        val state = createEmptyState()
        val statistics = createStatistics(totalInteractions = 47, uniqueAgentPairs = 4)

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("COORDINATION STATISTICS"), "Should have statistics header")
        assertTrue(output.contains("Total interactions:     47"), "Should show total interactions")
        assertTrue(output.contains("Unique agent pairs:     4"), "Should show unique pairs")
    }

    @Test
    fun `shows most active agent when present`() {
        val state = createEmptyState()
        val statistics = createStatistics(
            totalInteractions = 10,
            uniqueAgentPairs = 2,
            mostActiveAgent = "ProductMgrAgent",
        )

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("Most active agent:"), "Should have most active agent label")
        assertTrue(output.contains("ProductMgr"), "Should show shortened agent name")
    }

    @Test
    fun `calculates possible pairs correctly`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge("agent1", "agent2", 5, now, setOf(InteractionType.TICKET_ASSIGNED)),
                CoordinationEdge("agent2", "agent3", 3, now, setOf(InteractionType.DELEGATION)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
        val statistics = createStatistics(uniqueAgentPairs = 2)

        val output = renderer.render(state, statistics)

        // With 3 agents, possible pairs = 3 * 2 = 6
        assertTrue(output.contains("2/6 possible"), "Should calculate possible pairs as n*(n-1)")
    }

    @Test
    fun `displays average interactions per agent`() {
        val state = createEmptyState()
        val statistics = createStatistics(
            totalInteractions = 10,
            uniqueAgentPairs = 2,
            averageInteractionsPerAgent = 3.5,
        )

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("Avg per agent:"), "Should have average label")
        assertTrue(output.contains("3.5"), "Should show average with one decimal")
    }

    @Test
    fun `counts active meetings from state`() {
        val state = createStateWithMeetings(meetingCount = 2)
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("Active meetings:        2"), "Should show meeting count")
    }

    @Test
    fun `counts pending handoffs from state`() {
        val state = createStateWithHandoffs(handoffCount = 3)
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("Pending handoffs:       3"), "Should show handoff count")
    }

    @Test
    fun `counts human escalations from interactions`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = listOf(
                createInteraction("agent1", null, InteractionType.HUMAN_ESCALATION, now),
                createInteraction("agent2", null, InteractionType.HUMAN_ESCALATION, now),
                createInteraction("agent1", "agent2", InteractionType.TICKET_ASSIGNED, now),
            ),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("Human escalations:      2"), "Should count escalations")
    }

    @Test
    fun `renders interaction matrix header`() {
        val now = Clock.System.now()
        val state = createStateWithAgents()
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("INTERACTION MATRIX"), "Should have matrix header")
    }

    @Test
    fun `matrix includes all agent names as headers`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge("ProductMgrAgent", "CodeWriterAgent", 5, now, setOf(InteractionType.TICKET_ASSIGNED)),
                CoordinationEdge("CodeWriterAgent", "QAAgent", 3, now, setOf(InteractionType.DELEGATION)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("ProductMgr"), "Should contain ProductMgr")
        assertTrue(output.contains("CodeWriter"), "Should contain CodeWriter")
        assertTrue(output.contains("QA"), "Should contain QA")
    }

    @Test
    fun `matrix diagonal shows dashes`() {
        val state = createStateWithAgents()
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)
        val lines = output.lines()

        // Find matrix section
        val matrixStartIndex = lines.indexOfFirst { it.contains("INTERACTION MATRIX") }
        assertTrue(matrixStartIndex >= 0, "Should have matrix section")

        // Check that rows contain "-" for self-interactions
        val matrixRows = lines.drop(matrixStartIndex + 2) // Skip header and column names
        assertTrue(matrixRows.any { it.contains("-") }, "Should have dashes in diagonal")
    }

    @Test
    fun `matrix shows interaction counts`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge("agent1", "agent2", 15, now, setOf(InteractionType.TICKET_ASSIGNED)),
                CoordinationEdge("agent2", "agent1", 12, now, setOf(InteractionType.DELEGATION)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("15"), "Should show count 15")
        assertTrue(output.contains("12"), "Should show count 12")
    }

    @Test
    fun `matrix handles zero counts`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge("agent1", "agent2", 5, now, setOf(InteractionType.TICKET_ASSIGNED)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = listOf(
                createInteraction("agent1", "agent3", InteractionType.DELEGATION, now),
                createInteraction("agent2", "agent3", InteractionType.DELEGATION, now),
            ),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        // Should show 0 for pairs with no interactions
        assertTrue(output.contains("0"), "Should show zero counts")
    }

    @Test
    fun `matrix handles empty state`() {
        val state = createEmptyState()
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("INTERACTION MATRIX"), "Should have matrix header")
        assertTrue(output.contains("No interactions recorded"), "Should show empty message")
    }

    @Test
    fun `extracts agents from all state sources`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge("agent1", "agent2", 1, now, setOf(InteractionType.TICKET_ASSIGNED)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = listOf(
                createInteraction("agent3", "agent4", InteractionType.DELEGATION, now),
            ),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        // Should include agents from both edges and interactions
        assertTrue(output.contains("agent1"), "Should include agent from edges")
        assertTrue(output.contains("agent2"), "Should include agent from edges")
        assertTrue(output.contains("agent3"), "Should include agent from interactions")
        assertTrue(output.contains("agent4"), "Should include agent from interactions")
    }

    @Test
    fun `includes human in matrix when present`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = listOf(
                createInteraction("agent1", null, InteractionType.HUMAN_ESCALATION, now),
            ),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("human"), "Should include human in matrix")
    }

    @Test
    fun `shortens agent names in matrix`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = listOf(
                CoordinationEdge("ProductManagerAgent", "CodeWriterAgent", 5, now, setOf(InteractionType.TICKET_ASSIGNED)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
        val statistics = createStatistics()

        val output = renderer.render(state, statistics)

        assertTrue(output.contains("ProductManager"), "Should shorten ProductManagerAgent")
        assertTrue(output.contains("CodeWriter"), "Should shorten CodeWriterAgent")
        assertTrue(!output.contains("ProductManagerAgent"), "Should not show full name")
    }

    // Helper methods

    private fun createEmptyState(): CoordinationState {
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = Clock.System.now(),
        )
    }

    private fun createStateWithMeetings(meetingCount: Int): CoordinationState {
        val now = Clock.System.now()
        val meetings = List(meetingCount) { index ->
            link.socket.ampere.coordination.ActiveMeeting(
                meeting = link.socket.ampere.agents.events.meetings.Meeting(
                    id = "meeting-$index",
                    type = link.socket.ampere.agents.events.meetings.MeetingType.AdHoc(reason = "Test meeting"),
                    status = MeetingStatus.InProgress(
                        startedAt = now,
                        messagingDetails = link.socket.ampere.agents.events.meetings.MeetingMessagingDetails(
                            messageChannelId = "channel-$index",
                            messageThreadId = "thread-$index",
                        ),
                    ),
                    invitation = link.socket.ampere.agents.events.meetings.MeetingInvitation(
                        title = "Test Meeting $index",
                        agenda = emptyList(),
                        requiredParticipants = emptyList(),
                    ),
                ),
                messageCount = 0,
                participants = emptyList(),
            )
        }

        return CoordinationState(
            edges = emptyList(),
            activeMeetings = meetings,
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStateWithHandoffs(handoffCount: Int): CoordinationState {
        val now = Clock.System.now()
        val handoffs = List(handoffCount) { index ->
            link.socket.ampere.coordination.PendingHandoff(
                fromAgentId = "agent-$index",
                toAgentId = "agent-target",
                handoffType = InteractionType.TICKET_ASSIGNED,
                description = "Test handoff $index",
                timestamp = now,
                eventId = "evt-$index",
            )
        }

        return CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = handoffs,
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStateWithAgents(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = listOf(
                CoordinationEdge("agent1", "agent2", 5, now, setOf(InteractionType.TICKET_ASSIGNED)),
            ),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStatistics(
        totalInteractions: Int = 0,
        uniqueAgentPairs: Int = 0,
        mostActiveAgent: String? = null,
        averageInteractionsPerAgent: Double = 0.0,
    ): CoordinationStatistics {
        return CoordinationStatistics(
            totalInteractions = totalInteractions,
            uniqueAgentPairs = uniqueAgentPairs,
            mostActiveAgent = mostActiveAgent,
            interactionsByType = emptyMap(),
            averageInteractionsPerAgent = averageInteractionsPerAgent,
        )
    }

    private fun createInteraction(
        source: String,
        target: String?,
        type: InteractionType,
        timestamp: kotlinx.datetime.Instant,
    ): AgentInteraction {
        return AgentInteraction(
            sourceAgentId = source,
            targetAgentId = target,
            interactionType = type,
            timestamp = timestamp,
            eventId = "evt-123",
            context = null,
        )
    }
}
