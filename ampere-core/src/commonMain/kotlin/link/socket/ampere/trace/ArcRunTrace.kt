package link.socket.ampere.trace

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.domain.arc.CompletionRecord

/** The identity of one Arc execution. Alias of [RunId] (see AMPR-240) — same value, trace-package name. */
typealias ArcRunId = RunId

/** The Arc *configuration* identity (e.g. `"startup-saas"`) — distinct from a run instance. */
typealias ArcId = String

/**
 * The projection of one [ReplayWindow] — in v1 always an Arc run, so [runId] identifies it.
 *
 * @property completion What a run that did not close its loop — cancelled, or failed — left in
 *   place of a `Knowledge` entry: its persisted completion manifest (AMPR-359). `null` for a run
 *   that completed, and for one whose manifest was never written.
 */
@Serializable
data class ArcRunTrace(
    val runId: ArcRunId,
    val arcId: ArcId,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val phases: List<PropelPhase> = emptyList(),
    val completion: CompletionRecord? = null,
) {
    /** The window this trace covers. Derived from [runId]; not part of the serialized form. */
    val window: ReplayWindow get() = ReplayWindow.ArcRun(runId)
}

@Serializable
data class PropelPhase(
    val name: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val events: List<TraceEvent> = emptyList(),
    val modelInvocations: List<ModelInvocationTrace> = emptyList(),
    val memoryWrites: List<MemoryWriteTrace> = emptyList(),
    val toolCalls: List<ToolCallTrace> = emptyList(),
    val wattCost: WattCost = WattCost(),
)

@Serializable
data class TraceEvent(
    val eventId: String,
    val eventType: String,
    val timestamp: Instant,
    val sourceId: String,
    val summary: String,
    val payload: String? = null,
)

@Serializable
data class ModelInvocationTrace(
    val eventId: String,
    val agentId: String,
    val phaseName: String,
    val providerId: String,
    val modelId: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val routingReason: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedUsd: Double? = null,
    val latencyMs: Long? = null,
    val success: Boolean? = null,
    val errorType: String? = null,
    val wattCost: WattCost = WattCost(),
)

@Serializable
data class MemoryWriteTrace(
    val id: String,
    val phaseName: String,
    val timestamp: Instant,
    val memoryType: String,
    val sourceId: String? = null,
    val taskType: String? = null,
    val tags: List<String> = emptyList(),
    val approach: String? = null,
    val learnings: String? = null,
)

@Serializable
data class ToolCallTrace(
    val invocationId: String,
    val phaseName: String,
    val toolId: String,
    val toolName: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val durationMs: Long? = null,
    val success: Boolean? = null,
    val errorMessage: String? = null,
)

@Serializable
data class WattCost(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val estimatedUsd: Double? = null,
    val watts: Double = 0.0,
) {
    fun plus(other: WattCost): WattCost {
        return WattCost(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            estimatedUsd = estimatedUsd.plusNullable(other.estimatedUsd),
            watts = watts + other.watts,
        )
    }
}

private fun Double?.plusNullable(other: Double?): Double? = when {
    this == null -> other
    other == null -> this
    else -> this + other
}
