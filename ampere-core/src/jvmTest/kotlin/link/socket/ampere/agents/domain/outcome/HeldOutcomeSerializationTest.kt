package link.socket.ampere.agents.domain.outcome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.data.DEFAULT_JSON

/**
 * AMPR-425: the held variants are additive on the wire.
 *
 * Both new outcomes round-trip through the sealed serializers events and outcomes are
 * stored with, and `ToolExecutionCompleted.held` is a defaulted field that is never
 * written when false — so a completion stored before the field existed decodes as not
 * held, and every completion that is not held encodes exactly as it did before.
 */
class HeldOutcomeSerializationTest {

    private val json = DEFAULT_JSON
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `a held execution outcome round-trips with its reason and reference`() {
        val original: ExecutionOutcome = ExecutionOutcome.Held(
            executorId = "seat-doer",
            ticketId = "run-1",
            taskId = "step-1",
            executionStartTimestamp = at,
            executionEndTimestamp = at,
            reason = "waiting for approval to send",
            reference = "approval-17",
        )

        val decoded = json.decodeFromString(
            ExecutionOutcome.serializer(),
            json.encodeToString(ExecutionOutcome.serializer(), original),
        )

        val held = assertIs<ExecutionOutcome.Held>(decoded)
        assertEquals(original, held)
        assertFalse(decoded is ExecutionOutcome.Success, "a hold is not a success")
        assertFalse(decoded is ExecutionOutcome.Failure, "and not a failure")
    }

    @Test
    fun `a held execution outcome with no reference round-trips as null`() {
        val original: ExecutionOutcome = ExecutionOutcome.Held(
            executorId = "seat-doer",
            ticketId = "run-1",
            taskId = "step-1",
            executionStartTimestamp = at,
            executionEndTimestamp = at,
            reason = "waiting for approval to send",
        )

        val decoded = json.decodeFromString(
            ExecutionOutcome.serializer(),
            json.encodeToString(ExecutionOutcome.serializer(), original),
        )

        assertNull(assertIs<ExecutionOutcome.Held>(decoded).reference)
    }

    @Test
    fun `a held step outcome round-trips with its reason and reference`() {
        val original: StepOutcome = StepOutcome.Held(
            id = "step-1",
            stepDescription = "send the reply",
            startTimestamp = at,
            endTimestamp = at,
            reason = "waiting for approval to send",
            reference = "approval-17",
        )

        val decoded = json.decodeFromString(
            StepOutcome.serializer(),
            json.encodeToString(StepOutcome.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `a stored ToolExecutionCompleted without the held key decodes as not held`() {
        val notHeld = completion(held = false)

        val encoded = json.encodeToString(Event.serializer(), notHeld)
        assertFalse(
            encoded.contains("\"held\""),
            "a completion that is not held is written exactly as before the field existed: $encoded",
        )

        val decoded = assertIs<ToolEvent.ToolExecutionCompleted>(json.decodeFromString(Event.serializer(), encoded))
        assertFalse(decoded.held)
        assertEquals(notHeld, decoded)
    }

    @Test
    fun `a held ToolExecutionCompleted round-trips as held`() {
        val held = completion(held = true)

        val encoded = json.encodeToString(Event.serializer(), held)
        assertTrue(encoded.contains("\"held\":true"), encoded)

        val decoded = assertIs<ToolEvent.ToolExecutionCompleted>(json.decodeFromString(Event.serializer(), encoded))
        assertTrue(decoded.held)
        assertNull(decoded.errorMessage)
        assertTrue(
            decoded.getSummary({ "" }, { "" }).contains("HELD"),
            "the summary says held, not failed",
        )
    }

    private fun completion(held: Boolean) = ToolEvent.ToolExecutionCompleted(
        eventId = "event-1",
        timestamp = at,
        eventSource = EventSource.Agent("seat-doer"),
        urgency = Urgency.LOW,
        invocationId = "invocation-1",
        toolId = "send_reply",
        toolName = "Send reply",
        success = false,
        durationMs = 12,
        errorMessage = null,
        runId = "run-1",
        held = held,
    )
}
