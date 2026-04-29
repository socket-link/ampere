package link.socket.ampere.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import link.socket.ampere.data.DEFAULT_JSON

class ArcRunTraceTest {

    @Test
    fun `ArcRunTrace round trips through JSON`() {
        val trace = ArcRunTrace(
            runId = "run-123",
            arcId = "startup-saas",
            startedAt = Instant.fromEpochMilliseconds(1_000),
            endedAt = Instant.fromEpochMilliseconds(2_000),
            phases = listOf(
                PropelPhase(
                    name = "PLAN",
                    startedAt = Instant.fromEpochMilliseconds(1_000),
                    endedAt = Instant.fromEpochMilliseconds(2_000),
                    events = listOf(
                        TraceEvent(
                            eventId = "event-1",
                            eventType = "ProviderCallCompleted",
                            timestamp = Instant.fromEpochMilliseconds(2_000),
                            sourceId = "agent-1",
                            summary = "LLM call completed",
                            payload = """{"eventId":"event-1"}""",
                        ),
                    ),
                    modelInvocations = listOf(
                        ModelInvocationTrace(
                            eventId = "model-1",
                            agentId = "agent-1",
                            phaseName = "PLAN",
                            providerId = "openai",
                            modelId = "gpt-4.1",
                            startedAt = Instant.fromEpochMilliseconds(1_000),
                            endedAt = Instant.fromEpochMilliseconds(2_000),
                            inputTokens = 1_000,
                            outputTokens = 500,
                            estimatedUsd = 0.006,
                            latencyMs = 1_000,
                            success = true,
                            wattCost = WattCost(
                                inputTokens = 1_000,
                                outputTokens = 500,
                                estimatedUsd = 0.006,
                                watts = 1.5,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val encoded = DEFAULT_JSON.encodeToString(trace)
        val decoded = DEFAULT_JSON.decodeFromString(ArcRunTrace.serializer(), encoded)

        assertEquals(trace, decoded)
    }
}
