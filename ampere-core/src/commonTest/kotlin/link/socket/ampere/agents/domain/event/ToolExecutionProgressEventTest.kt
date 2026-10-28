package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.data.DEFAULT_JSON

/**
 * AMPR-495: the wire shape of `ToolExecutionProgress`. It decodes through the `Event`
 * polymorphic scope like every other event, a `Writing` carries its path and operation, and
 * every other kind leaves both keys out.
 */
class ToolExecutionProgressEventTest {

    @Test
    fun `a Writing progress round-trips through the Event scope with its path and operation`() {
        val event = progress(
            kind = "Writing",
            detail = "src/Greeting.kt",
            filePath = "src/Greeting.kt",
            operation = EnvironmentFileOperation.CREATE,
        )

        val encoded = DEFAULT_JSON.encodeToString(Event.serializer(), event)
        val decoded = assertIs<ToolEvent.ToolExecutionProgress>(
            DEFAULT_JSON.decodeFromString(Event.serializer(), encoded),
        )

        assertEquals(event, decoded)
        assertEquals("ToolExecutionProgress", decoded.eventType)
    }

    @Test
    fun `a progress that wrote no file encodes no path and no operation`() {
        val event = progress(kind = "Planning", detail = "Delegating the step")

        val encoded = DEFAULT_JSON.encodeToString(Event.serializer(), event)

        assertFalse("filePath" in encoded, encoded)
        assertFalse("operation" in encoded, encoded)
        assertEquals(event, DEFAULT_JSON.decodeFromString(Event.serializer(), encoded))
    }

    @Test
    fun `the summary names the tool and the kind and the file it wrote`() {
        val format: (Urgency) -> String = { "[${it.name}]" }
        val source: (EventSource) -> String = { it.getIdentifier() }

        val writing = progress(
            kind = "Writing",
            detail = "src/Greeting.kt",
            filePath = "src/Greeting.kt",
            operation = EnvironmentFileOperation.MODIFY,
        ).getSummary(format, source)
        val planning = progress(kind = "Planning", detail = "Delegating the step").getSummary(format, source)

        assertTrue(writing.contains("Code Change (harness)"), writing)
        assertTrue(writing.contains("Writing: MODIFY src/Greeting.kt"), writing)
        assertTrue(planning.contains("Planning: Delegating the step"), planning)
        assertTrue(planning.endsWith("[LOW]"), planning)
    }

    private fun progress(
        kind: String,
        detail: String,
        filePath: String? = null,
        operation: EnvironmentFileOperation? = null,
    ) = ToolEvent.ToolExecutionProgress(
        eventId = "progress-1",
        timestamp = Instant.fromEpochMilliseconds(1_000),
        eventSource = EventSource.Agent("code-agent"),
        invocationId = "call-1",
        toolId = "harness_code_change",
        toolName = "Code Change (harness)",
        runId = "run-1",
        executorId = "opencode",
        kind = kind,
        detail = detail,
        filePath = filePath,
        operation = operation,
    )
}
