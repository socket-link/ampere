package link.socket.ampere.renderer

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.Urgency
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertTrue

class EventRendererTest {

    /**
     * Helper to capture terminal output for testing.
     * Mordant writes to System.out, so we redirect it to capture output.
     */
    private fun captureTerminalOutput(block: (Terminal, EventRenderer) -> Unit): String {
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)
        val originalOut = System.out

        return try {
            System.setOut(printStream)
            val terminal = Terminal()
            val renderer = EventRenderer(terminal, TimeZone.UTC)
            block(terminal, renderer)
            outputStream.toString()
        } finally {
            System.setOut(originalOut)
        }
    }

    /**
     * Helper to create a test TaskCreated event.
     */
    private fun taskEvent(
        eventId: String = "evt-task-1",
        timestamp: Instant = Clock.System.now(),
        source: EventSource = EventSource.Agent("agent-test"),
        urgency: Urgency = Urgency.HIGH,
        taskId: String = "TASK-123",
        description: String = "Implement authentication",
        assignedTo: String? = "agent-auth"
    ): Event.TaskCreated = Event.TaskCreated(
        eventId = eventId,
        timestamp = timestamp,
        eventSource = source,
        urgency = urgency,
        taskId = taskId,
        description = description,
        assignedTo = assignedTo
    )

    /**
     * Helper to create a test QuestionRaised event.
     */
    private fun questionEvent(
        eventId: String = "evt-question-1",
        timestamp: Instant = Clock.System.now(),
        source: EventSource = EventSource.Agent("agent-test"),
        urgency: Urgency = Urgency.MEDIUM,
        questionText: String = "How should we handle this error?",
        context: String = "During database migration"
    ): Event.QuestionRaised = Event.QuestionRaised(
        eventId = eventId,
        timestamp = timestamp,
        eventSource = source,
        urgency = urgency,
        questionText = questionText,
        context = context
    )

    /**
     * Helper to create a test CodeSubmitted event.
     */
    private fun codeEvent(
        eventId: String = "evt-code-1",
        timestamp: Instant = Clock.System.now(),
        source: EventSource = EventSource.Agent("agent-dev"),
        urgency: Urgency = Urgency.LOW,
        filePath: String = "src/main/Auth.kt",
        changeDescription: String = "Add OAuth support",
        reviewRequired: Boolean = true,
        assignedTo: String? = "agent-reviewer"
    ): Event.CodeSubmitted = Event.CodeSubmitted(
        eventId = eventId,
        timestamp = timestamp,
        eventSource = source,
        urgency = urgency,
        filePath = filePath,
        changeDescription = changeDescription,
        reviewRequired = reviewRequired,
        assignedTo = assignedTo
    )

    @Test
    fun `render TaskCreated event shows task ID, description, and assignment`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                taskEvent(
                    taskId = "TASK-456",
                    description = "Fix login bug",
                    assignedTo = "agent-bugfixer"
                )
            )
        }

        assertContains(output, "TASK-456")
        assertContains(output, "Fix login bug")
        assertContains(output, "assigned to: agent-bugfixer")
        assertContains(output, "TaskCreated")
    }

    @Test
    fun `render QuestionRaised event shows question text and context`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                questionEvent(
                    questionText = "Should we use Redis or Memcached?",
                    context = "Caching layer design"
                )
            )
        }

        assertContains(output, "Should we use Redis or Memcached?")
        assertContains(output, "Caching layer design")
        assertContains(output, "QuestionRaised")
    }

    @Test
    fun `render CodeSubmitted event shows file path and change description`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                codeEvent(
                    filePath = "src/auth/OAuth.kt",
                    changeDescription = "Implement OAuth2 flow",
                    reviewRequired = true,
                    assignedTo = "agent-security"
                )
            )
        }

        assertContains(output, "src/auth/OAuth.kt")
        assertContains(output, "Implement OAuth2 flow")
        assertContains(output, "review required")
        assertContains(output, "agent-security")
        assertContains(output, "CodeSubmitted")
    }

    @Test
    fun `render formats timestamp as HH-MM-SS`() {
        // Create an event with a specific timestamp
        val timestamp = Instant.parse("2024-01-15T14:32:18Z")
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(taskEvent(timestamp = timestamp))
        }

        // Should show time in UTC (as configured in captureTerminalOutput)
        assertContains(output, "14:32:18")
    }

    @Test
    fun `render shows urgency level for all event types`() {
        val highUrgencyOutput = captureTerminalOutput { _, renderer ->
            renderer.render(taskEvent(urgency = Urgency.HIGH))
        }
        assertContains(highUrgencyOutput, "[HIGH]")

        val mediumUrgencyOutput = captureTerminalOutput { _, renderer ->
            renderer.render(questionEvent(urgency = Urgency.MEDIUM))
        }
        assertContains(mediumUrgencyOutput, "[MEDIUM]")

        val lowUrgencyOutput = captureTerminalOutput { _, renderer ->
            renderer.render(codeEvent(urgency = Urgency.LOW))
        }
        assertContains(lowUrgencyOutput, "[LOW]")
    }

    @Test
    fun `render shows event source for agent events`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                taskEvent(source = EventSource.Agent("agent-orchestrator"))
            )
        }

        assertContains(output, "agent-orchestrator")
    }

    @Test
    fun `render shows event source for human events`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                taskEvent(source = EventSource.Human)
            )
        }

        assertContains(output, "human")
    }

    @Test
    fun `render TaskCreated without assignment doesn't show assigned to`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                taskEvent(assignedTo = null)
            )
        }

        assertTrue(!output.contains("assigned to:"))
    }

    @Test
    fun `render QuestionRaised truncates long context`() {
        val longContext = "This is a very long context string that should be truncated " +
            "because it exceeds the maximum length of 60 characters for display purposes"

        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                questionEvent(context = longContext)
            )
        }

        // Should be truncated with ellipsis
        assertContains(output, "...")
        // Should not contain the full string
        assertTrue(output.length < longContext.length + 100) // Some buffer for formatting
    }

    @Test
    fun `render CodeSubmitted without review shows no review indicator`() {
        val output = captureTerminalOutput { _, renderer ->
            renderer.render(
                codeEvent(reviewRequired = false, assignedTo = null)
            )
        }

        assertTrue(!output.contains("review required"))
    }

    @Test
    fun `render includes appropriate icons for event types`() {
        val taskOutput = captureTerminalOutput { _, renderer ->
            renderer.render(taskEvent())
        }
        assertContains(taskOutput, "📋")

        val questionOutput = captureTerminalOutput { _, renderer ->
            renderer.render(questionEvent())
        }
        assertContains(questionOutput, "❓")

        val codeOutput = captureTerminalOutput { _, renderer ->
            renderer.render(codeEvent())
        }
        assertContains(codeOutput, "💻")
    }

    @Test
    fun `render handles different timezones correctly`() {
        val timestamp = Instant.parse("2024-01-15T14:32:18Z")

        // Create renderer with specific timezone
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)
        val originalOut = System.out

        try {
            System.setOut(printStream)
            val terminal = Terminal()
            val renderer = EventRenderer(terminal, TimeZone.of("America/New_York"))
            renderer.render(taskEvent(timestamp = timestamp))

            val output = outputStream.toString()

            // In EST/EDT, this should show a different time than UTC
            // 14:32:18 UTC = 09:32:18 EST (UTC-5)
            val expectedTime = timestamp.toLocalDateTime(TimeZone.of("America/New_York"))
            val expectedTimeStr = buildString {
                append(expectedTime.hour.toString().padStart(2, '0'))
                append(":")
                append(expectedTime.minute.toString().padStart(2, '0'))
                append(":")
                append(expectedTime.second.toString().padStart(2, '0'))
            }

            assertContains(output, expectedTimeStr)
        } finally {
            System.setOut(originalOut)
        }
    }
}
