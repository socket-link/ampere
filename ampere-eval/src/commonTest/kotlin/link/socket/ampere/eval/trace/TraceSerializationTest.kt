package link.socket.ampere.eval.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.data.DEFAULT_JSON

/** AMPR-183 task 1.1 validation. */
class TraceSerializationTest {

    private val json = DEFAULT_JSON

    @Test
    fun `round-trips a 3-event trace to identical content`() {
        val original = Trace(
            id = "trace-1",
            runId = "run-1",
            arcId = "arc-1",
            createdAt = 1_000L,
            events = listOf(
                TraceEvent(
                    index = 0,
                    timestamp = 10L,
                    type = "TaskCreated",
                    payload = buildJsonObject { put("taskId", "T-1") },
                ),
                TraceEvent(
                    index = 1,
                    timestamp = 20L,
                    type = "QuestionRaised",
                    payload = buildJsonObject { put("questionText", "why?") },
                ),
                TraceEvent(
                    index = 2,
                    timestamp = 30L,
                    type = "CodeSubmitted",
                    payload = buildJsonObject { put("filePath", "Main.kt") },
                ),
            ),
        )

        val encoded = json.encodeToString(Trace.serializer(), original)
        val decoded = json.decodeFromString(Trace.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(3, decoded.size)
        assertEquals(original.events, decoded.events)
    }
}
