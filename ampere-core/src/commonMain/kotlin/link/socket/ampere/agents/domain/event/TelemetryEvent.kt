package link.socket.ampere.agents.domain.event

import kotlin.math.roundToLong
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * Public telemetry events emitted around each LLM call.
 *
 * These events are intentionally shaped as stable API-facing contracts so
 * external consumers can observe provider routing, token usage, and latency
 * without depending on internal relay implementation details.
 */
@Serializable
sealed interface TelemetryEvent : Event {

    val workflowId: String?
    val agentId: AgentId
    val cognitivePhase: CognitivePhase?
    val providerId: ProviderId
    val modelId: String
}

@Serializable
@SerialName("ProviderCallStartedEvent")
data class ProviderCallStartedEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,
    override val workflowId: String? = null,
    override val agentId: AgentId,
    override val cognitivePhase: CognitivePhase? = null,
    override val providerId: ProviderId,
    override val modelId: String,
    val routingReason: String,
) : TelemetryEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("LLM call started: $providerId/$modelId")
        cognitivePhase?.let { append(" [${it.name}]") }
        append(" (route: $routingReason)")
        workflowId?.let { append(" workflow=$it") }
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "ProviderCallStarted"
    }
}

@Serializable
@SerialName("ProviderCallCompletedEvent")
data class ProviderCallCompletedEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,
    override val workflowId: String? = null,
    override val agentId: AgentId,
    override val cognitivePhase: CognitivePhase? = null,
    override val providerId: ProviderId,
    override val modelId: String,
    val usage: TokenUsage = TokenUsage(),
    val latencyMs: Long,
    val success: Boolean,
    val errorType: String? = null,
) : TelemetryEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("LLM call ${if (success) "completed" else "failed"}: $providerId/$modelId")
        cognitivePhase?.let { append(" [${it.name}]") }
        append(" (")
        var wroteDetail = false
        if (usage.inputTokens != null || usage.outputTokens != null) {
            append(
                "${usage.inputTokens?.formatGrouped() ?: "?"} in / ${usage.outputTokens?.formatGrouped() ?: "?"} out",
            )
            wroteDetail = true
        }
        usage.estimatedCost?.let { estimatedCost ->
            if (wroteDetail) append(", ")
            append("~${estimatedCost.formatUsd()}")
            wroteDetail = true
        }
        if (wroteDetail) append(", ")
        append("${latencyMs}ms")
        append(")")
        errorType?.let { append(" [$it]") }
        workflowId?.let { append(" workflow=$it") }
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "ProviderCallCompleted"
    }
}

private fun Int.formatGrouped(): String = toString().formatGroupedDigits()

private fun Long.formatGrouped(): String = toString().formatGroupedDigits()

private fun String.formatGroupedDigits(): String {
    if (length <= 3) return this

    val firstGroupSize = if (length % 3 == 0) 3 else length % 3
    return buildString {
        append(this@formatGroupedDigits.substring(0, firstGroupSize))
        var index = firstGroupSize
        while (index < this@formatGroupedDigits.length) {
            append(",")
            append(this@formatGroupedDigits.substring(index, index + 3))
            index += 3
        }
    }
}

private fun Double.formatUsd(): String {
    val scaled = (this * 10_000).roundToLong()
    val absoluteScaled = if (scaled < 0) -scaled else scaled
    val dollars = absoluteScaled / 10_000
    val fraction = (absoluteScaled % 10_000).toString().padStart(4, '0')

    return buildString {
        if (scaled < 0) append("-")
        append("$")
        append(dollars.formatGrouped())
        append(".")
        append(fraction)
    }
}
