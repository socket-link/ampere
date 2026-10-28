package link.socket.ampere.agents.domain.event

import kotlin.math.roundToLong
import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

    /** The maker of the model — `anthropic` for Claude — never a gateway the call travelled through (AMPR-436). */
    val providerId: ProviderId
    val modelId: String
}

@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * The route the call travelled when that was not its maker's own endpoint — a
     * gateway such as `openrouter` (AMPR-436). [providerId] names the maker and is
     * never a gateway. Null means direct or not stated.
     *
     * Never encoded while null, so a direct call's stored row is byte-identical to one
     * written before this field existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val via: ProviderId? = null,
    /**
     * The relay chose no model: the transport picks one server-side, and the
     * configuration the relay returned is only a placeholder (AMPR-503). When true,
     * [providerId] and [modelId] name that placeholder, not a model, and a reader must
     * not show them; the paired [ProviderCallCompletedEvent] names what served.
     *
     * Never encoded while false, so every other call's stored row is byte-identical to
     * one written before this field existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val transportChoosesModel: Boolean = false,
) : TelemetryEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        if (transportChoosesModel) {
            append("LLM call started: model chosen by transport")
        } else {
            append("LLM call started: $providerId/$modelId")
        }
        via?.let { append(" via $it") }
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

@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * Which rule the *transport* matched, in its own vocabulary, when it reported one
     * (AMPR-391) — a consumer's proxy routing server-side on a tier or a failover order.
     *
     * Null is the ordinary case and means the relay's reason on this call's
     * [ProviderCallStartedEvent] is the whole story; a reader resolves the reason as
     * `completed.servedRoutingReason ?: started.routingReason`. Distinct from that field
     * rather than overwriting it, because the two answer different questions: why the call
     * was *routed* this way, and why it was *served* that way.
     */
    val servedRoutingReason: String? = null,
    /**
     * The route the call travelled when that was not its maker's own endpoint — a
     * gateway such as `openrouter` (AMPR-436). [providerId] names the maker and is
     * never a gateway. Null means direct or not stated.
     *
     * Never encoded while null, so a direct call's stored row is byte-identical to one
     * written before this field existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val via: ProviderId? = null,
    /**
     * The call was transport-routed (its start carried
     * [ProviderCallStartedEvent.transportChoosesModel]) and the transport never said what
     * served it (AMPR-503) — most often because it failed before anything did. When true,
     * [providerId] and [modelId] are the request's placeholder, not a model, and a reader
     * must not show them: a backend timeout is not "that model couldn't answer".
     *
     * Never encoded while false, so every other call's stored row is byte-identical to
     * one written before this field existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val transportChoosesModel: Boolean = false,
) : TelemetryEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        when {
            !transportChoosesModel ->
                append("LLM call ${if (success) "completed" else "failed"}: $providerId/$modelId")
            success -> append("LLM call completed: model not reported by transport")
            else -> append("LLM call failed: no model served")
        }
        via?.let { append(" via $it") }
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
