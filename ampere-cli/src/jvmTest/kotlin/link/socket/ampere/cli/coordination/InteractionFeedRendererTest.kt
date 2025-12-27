package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.coordination.AgentInteraction
import link.socket.ampere.coordination.InteractionType
import kotlin.time.Duration.Companion.minutes

class InteractionFeedRendererTest {

    private val renderer = InteractionFeedRenderer()

    @Test
    fun `renders empty message when no interactions`() {
        val output = renderer.render(emptyList())

        assertEquals("No recent interactions", output)
    }

    @Test
    fun `renders single interaction with correct format`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "ProductMgrAgent",
                target = "CodeWriterAgent",
                type = InteractionType.TICKET_ASSIGNED,
                context = "Implement feature X",
            ),
        )

        val output = renderer.render(interactions)

        assertTrue(output.contains("ProductMgr"), "Should contain shortened source name")
        assertTrue(output.contains("CodeWriter"), "Should contain shortened target name")
        assertTrue(output.contains("──▶"), "Should contain forward arrow")
        assertTrue(output.contains("ticket"), "Should contain interaction type")
        assertTrue(output.contains("Implement feature X"), "Should contain context")
    }

    @Test
    fun `renders multiple interactions in order`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now - 2.minutes,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.TICKET_ASSIGNED,
            ),
            createInteraction(
                timestamp = now - 1.minutes,
                source = "Agent2",
                target = "Agent3",
                type = InteractionType.DELEGATION,
            ),
            createInteraction(
                timestamp = now,
                source = "Agent3",
                target = "Agent1",
                type = InteractionType.HELP_REQUEST,
            ),
        )

        val output = renderer.render(interactions)
        val lines = output.lines()

        assertEquals(3, lines.size, "Should have 3 interaction lines")
    }

    @Test
    fun `uses backward arrow for response types`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.CLARIFICATION_RESPONSE,
            ),
        )

        val output = renderer.render(interactions)

        assertTrue(output.contains("◀──"), "Should contain backward arrow for response type")
    }

    @Test
    fun `uses forward arrow for request types`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.CLARIFICATION_REQUEST,
            ),
        )

        val output = renderer.render(interactions)

        assertTrue(output.contains("──▶"), "Should contain forward arrow for request type")
    }

    @Test
    fun `all response types use backward arrow`() {
        val now = Clock.System.now()
        val responseTypes = listOf(
            InteractionType.CLARIFICATION_RESPONSE,
            InteractionType.HELP_RESPONSE,
            InteractionType.REVIEW_COMPLETE,
            InteractionType.HUMAN_RESPONSE,
        )

        responseTypes.forEach { type ->
            val interactions = listOf(
                createInteraction(
                    timestamp = now,
                    source = "Agent1",
                    target = "Agent2",
                    type = type,
                ),
            )

            val output = renderer.render(interactions)
            assertTrue(
                output.contains("◀──"),
                "$type should use backward arrow",
            )
        }
    }

    @Test
    fun `truncates long summaries in normal mode`() {
        val now = Clock.System.now()
        val longContext = "This is a very long summary that exceeds the truncation limit and should be cut off"
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.TICKET_ASSIGNED,
                context = longContext,
            ),
        )

        val output = renderer.render(interactions, verbose = false)

        assertTrue(output.contains("..."), "Should truncate with ellipsis")
        assertTrue(output.length < longContext.length + 100, "Output should be shorter than full context")
    }

    @Test
    fun `shows full summary in verbose mode`() {
        val now = Clock.System.now()
        val longContext = "This is a very long summary that exceeds the truncation limit and should be shown in full"
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.TICKET_ASSIGNED,
                context = longContext,
            ),
        )

        val output = renderer.render(interactions, verbose = true)

        assertTrue(output.contains(longContext), "Should contain full context in verbose mode")
    }

    @Test
    fun `verbose mode adds second line for long summaries`() {
        val now = Clock.System.now()
        val longContext = "This is a very long summary that exceeds the truncation limit"
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.TICKET_ASSIGNED,
                context = longContext,
            ),
        )

        val output = renderer.render(interactions, verbose = true)
        val lines = output.lines()

        assertTrue(lines.size >= 2, "Verbose mode should add extra line for long summary")
    }

    @Test
    fun `handles maxLines overflow with indicator`() {
        val now = Clock.System.now()
        val interactions = List(10) { index ->
            createInteraction(
                timestamp = now,
                source = "Agent$index",
                target = "AgentNext",
                type = InteractionType.TICKET_ASSIGNED,
            )
        }

        val output = renderer.render(interactions, maxLines = 3)
        val lines = output.lines()

        assertTrue(lines.size <= 3, "Should not exceed maxLines")
        assertTrue(output.contains("... and"), "Should show overflow indicator")
        assertTrue(output.contains("more"), "Should show 'more' text")
    }

    @Test
    fun `unlimited maxLines shows all interactions`() {
        val now = Clock.System.now()
        val interactions = List(10) { index ->
            createInteraction(
                timestamp = now,
                source = "Agent$index",
                target = "AgentNext",
                type = InteractionType.TICKET_ASSIGNED,
            )
        }

        val output = renderer.render(interactions, maxLines = 0)

        assertTrue(!output.contains("... and"), "Should not show overflow with maxLines=0")
        assertEquals(10, output.lines().size, "Should show all 10 interactions")
    }

    @Test
    fun `formats time as HH MM SS`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = "Agent2",
                type = InteractionType.TICKET_ASSIGNED,
            ),
        )

        val output = renderer.render(interactions)

        // Should contain time in format HH:MM:SS (e.g., "14:23:45")
        val timeRegex = Regex("""\d{2}:\d{2}:\d{2}""")
        assertTrue(timeRegex.containsMatchIn(output), "Should contain time in HH:MM:SS format")
    }

    @Test
    fun `shortens agent names by removing Agent suffix`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "ProductManagerAgent",
                target = "CodeWriterAgent",
                type = InteractionType.TICKET_ASSIGNED,
            ),
        )

        val output = renderer.render(interactions)

        assertTrue(output.contains("ProductManager"), "Should contain shortened source")
        assertTrue(output.contains("CodeWriter"), "Should contain shortened target")
        assertTrue(!output.contains("ProductManagerAgent"), "Should not contain full agent name")
        assertTrue(!output.contains("CodeWriterAgent"), "Should not contain full agent name")
    }

    @Test
    fun `handles null target as human`() {
        val now = Clock.System.now()
        val interactions = listOf(
            createInteraction(
                timestamp = now,
                source = "Agent1",
                target = null,
                type = InteractionType.HUMAN_ESCALATION,
            ),
        )

        val output = renderer.render(interactions)

        assertTrue(output.contains("human"), "Should show 'human' for null target")
    }

    @Test
    fun `formats different interaction types correctly`() {
        val now = Clock.System.now()
        val typeTests = mapOf(
            InteractionType.TICKET_ASSIGNED to "ticket",
            InteractionType.CLARIFICATION_REQUEST to "clarify?",
            InteractionType.REVIEW_REQUEST to "review?",
            InteractionType.MEETING_INVITE to "meeting invite",
            InteractionType.DELEGATION to "delegated",
            InteractionType.HUMAN_ESCALATION to "escalation",
        )

        typeTests.forEach { (type, expectedLabel) ->
            val interactions = listOf(
                createInteraction(
                    timestamp = now,
                    source = "Agent1",
                    target = "Agent2",
                    type = type,
                ),
            )

            val output = renderer.render(interactions)
            assertTrue(
                output.contains(expectedLabel),
                "$type should display as '$expectedLabel'",
            )
        }
    }

    // Helper methods

    private fun createInteraction(
        timestamp: Instant,
        source: String,
        target: String?,
        type: InteractionType,
        context: String? = null,
    ): AgentInteraction {
        return AgentInteraction(
            sourceAgentId = source,
            targetAgentId = target,
            interactionType = type,
            timestamp = timestamp,
            eventId = "evt-123",
            context = context,
        )
    }
}
