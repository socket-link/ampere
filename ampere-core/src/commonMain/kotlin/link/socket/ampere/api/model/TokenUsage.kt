package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Per-call token accounting emitted through telemetry events.
 *
 * Values are nullable because custom or mocked providers may not expose
 * usage metadata for every invocation.
 */
@Serializable
data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCost: Double? = null,
)
