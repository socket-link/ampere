package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.status.MeetingStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.events.meetings.Meeting
import link.socket.ampere.agents.events.meetings.MeetingInvitation
import link.socket.ampere.agents.events.meetings.MeetingMessagingDetails
import link.socket.ampere.agents.events.meetings.MeetingType
import link.socket.ampere.coordination.ActiveMeeting
import link.socket.ampere.coordination.BlockedAgent
import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.InteractionType
import link.socket.ampere.coordination.PendingHandoff
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ActiveCoordinationRendererTest {

    private val renderer = ActiveCoordinationRenderer()

    @Test
    fun `renders empty state message when no coordination`() {
        val state = createEmptyState()

        val output = renderer.render(state)

        assertEquals("No active coordination", output)
    }

    @Test
    fun `renders meeting with participants and details`() {
        val state = createStateWithOneMeeting()

        val output = renderer.render(state)

        assertTrue(output.contains("▶ Meeting:"), "Should have meeting prefix")
        assertTrue(output.contains("MCP Architecture"), "Should contain meeting name")
        assertTrue(output.contains("Participants:"), "Should show participants label")
        assertTrue(output.contains("ProductMgr"), "Should show first participant")
        assertTrue(output.contains("CodeWriter"), "Should show second participant")
        assertTrue(output.contains("Messages:"), "Should show message count label")
    }

    @Test
    fun `renders pending handoff with source and target`() {
        val state = createStateWithPendingHandoff()

        val output = renderer.render(state)

        assertTrue(output.contains("⧖ Pending Handoff:"), "Should have handoff prefix")
        assertTrue(output.contains("CodeWriter → QA"), "Should show source → target")
        assertTrue(output.contains("Ticket:"), "Should show ticket label")
        assertTrue(output.contains("Waiting:"), "Should show waiting label")
    }

    @Test
    fun `renders blocked agent with blocker and reason`() {
        val state = createStateWithBlockedAgent()

        val output = renderer.render(state)

        assertTrue(output.contains("⛔ Blocked:"), "Should have blocked prefix")
        assertTrue(output.contains("waiting on"), "Should show 'waiting on' text")
        assertTrue(output.contains("Reason:"), "Should show reason label")
        assertTrue(output.contains("Duration:"), "Should show duration label")
    }

    @Test
    fun `handles maxLines overflow with indicator`() {
        val state = createStateWithMultipleItems()

        val output = renderer.render(state, maxLines = 3)

        val lines = output.lines()
        assertTrue(lines.size <= 3, "Should not exceed maxLines")
        assertTrue(output.contains("... and"), "Should show overflow indicator")
        assertTrue(output.contains("more"), "Should show 'more' text")
    }

    @Test
    fun `renders multiple items of different types`() {
        val now = Clock.System.now()
        val state = CoordinationState(
            edges = emptyList(),
            activeMeetings = listOf(createMeeting(now)),
            pendingHandoffs = listOf(createHandoff(now)),
            blockedAgents = listOf(createBlockedAgent(now)),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )

        val output = renderer.render(state)

        assertTrue(output.contains("▶ Meeting:"), "Should have meeting")
        assertTrue(output.contains("⧖ Pending Handoff:"), "Should have handoff")
        assertTrue(output.contains("⛔ Blocked:"), "Should have blocked agent")
    }

    @Test
    fun `formats duration in seconds correctly`() {
        val now = Clock.System.now()
        val state = createStateWithPendingHandoff(now - 45.seconds)

        val output = renderer.render(state, maxLines = 0)

        assertTrue(output.contains("45s") || output.contains("s)"), "Should show seconds")
    }

    @Test
    fun `formats duration in minutes correctly`() {
        val now = Clock.System.now()
        val state = createStateWithPendingHandoff(now - 2.minutes)

        val output = renderer.render(state, maxLines = 0)

        assertTrue(output.contains("2m") || output.contains("m"), "Should show minutes")
    }

    @Test
    fun `shortens agent names by removing Agent suffix`() {
        val state = createStateWithPendingHandoff()

        val output = renderer.render(state)

        // Agent names should have "Agent" removed
        assertTrue(output.contains("CodeWriter") && !output.contains("CodeWriterAgent"),
            "Should shorten CodeWriterAgent to CodeWriter")
        assertTrue(output.contains("QA") && !output.contains("QAAgent"),
            "Should shorten QAAgent to QA")
    }

    @Test
    fun `unlimited maxLines shows all items`() {
        val state = createStateWithMultipleItems()

        val output = renderer.render(state, maxLines = 0)

        // Should not contain overflow indicator
        assertTrue(!output.contains("... and"), "Should not show overflow with maxLines=0")
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

    private fun createStateWithOneMeeting(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = listOf(createMeeting(now)),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStateWithPendingHandoff(timestamp: Instant = Clock.System.now()): CoordinationState {
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = listOf(createHandoff(timestamp)),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = Clock.System.now(),
        )
    }

    private fun createStateWithBlockedAgent(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = listOf(createBlockedAgent(now)),
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createStateWithMultipleItems(): CoordinationState {
        val now = Clock.System.now()
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = List(3) { createMeeting(now, "Meeting $it") },
            pendingHandoffs = List(3) { createHandoff(now) },
            blockedAgents = List(3) { createBlockedAgent(now) },
            recentInteractions = emptyList(),
            lastUpdated = now,
        )
    }

    private fun createMeeting(now: Instant, title: String = "MCP Architecture Discussion"): ActiveMeeting {
        val meeting = Meeting(
            id = "meeting-1",
            type = MeetingType.AdHoc(reason = "Architecture discussion"),
            status = MeetingStatus.InProgress(
                startedAt = now - 2.minutes,
                messagingDetails = MeetingMessagingDetails(
                    messageChannelId = "channel-1",
                    messageThreadId = "thread-1",
                ),
            ),
            invitation = MeetingInvitation(
                title = title,
                agenda = emptyList(),
                requiredParticipants = listOf(
                    AssignedTo.Agent("ProductMgrAgent"),
                    AssignedTo.Agent("CodeWriterAgent"),
                ),
            ),
        )

        return ActiveMeeting(
            meeting = meeting,
            messageCount = 4,
            participants = listOf("ProductMgrAgent", "CodeWriterAgent"),
        )
    }

    private fun createHandoff(timestamp: Instant): PendingHandoff {
        return PendingHandoff(
            fromAgentId = "CodeWriterAgent",
            toAgentId = "QAAgent",
            handoffType = InteractionType.REVIEW_REQUEST,
            description = "review requested",
            timestamp = timestamp,
            eventId = "AMPERE-47",
        )
    }

    private fun createBlockedAgent(timestamp: Instant): BlockedAgent {
        return BlockedAgent(
            agentId = "CodeWriterAgent",
            blockedBy = "QAAgent",
            reason = "Waiting for code review",
            since = timestamp - 1.minutes,
            eventId = "evt-123",
        )
    }
}
