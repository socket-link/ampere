package link.socket.ampere.agents.domain.routing.local

import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * What is known about whether the device can serve a generation on its own.
 *
 * [Unknown] is a real answer, not a placeholder for [Unavailable]: nothing has
 * probed the engine and no call has been routed yet, so a surface should say it
 * does not know rather than claim the model is missing.
 */
sealed interface OnDeviceAvailability {

    /** Nothing has reported on the engine yet. */
    data object Unknown : OnDeviceAvailability

    /**
     * The engine can serve a generation.
     *
     * @property modelId The on-device model that would serve it, if reported.
     * @property maxContextTokens The largest prompt it accepts, if reported.
     */
    data class Available(
        val modelId: String? = null,
        val maxContextTokens: Int? = null,
    ) : OnDeviceAvailability

    /**
     * The engine cannot serve a generation right now.
     *
     * @property reason The engine's own reason code (for example
     *   `apple_intelligence_not_enabled`), or the relay's when it routed around
     *   the device without one. Machine-shaped; a surface translates it.
     */
    data class Unavailable(val reason: String? = null) : OnDeviceAvailability
}

/**
 * The identity of one model call, as its telemetry carries it.
 *
 * The same five fields `ArcTraceProjection` pairs a
 * [ProviderCallStartedEvent][link.socket.ampere.agents.domain.event.ProviderCallStartedEvent]
 * with its completion on, so "in flight" here means what it means in the trace.
 */
data class OnDeviceCallKey(
    val workflowId: String?,
    val agentId: AgentId,
    val providerId: ProviderId,
    val modelId: String,
    val cognitivePhase: CognitivePhase?,
)

/**
 * How the most recent on-device call ended.
 *
 * @property call Which call it was.
 * @property completedAt The completion event's own timestamp.
 * @property latencyMs Wall-clock duration the completion reported.
 * @property success Whether the engine produced a completion.
 * @property errorType The completion's `errorType` when it did not.
 */
data class OnDeviceCallSummary(
    val call: OnDeviceCallKey,
    val completedAt: Instant,
    val latencyMs: Long,
    val success: Boolean,
    val errorType: String? = null,
)

/**
 * The most recent time the relay wanted the device and routed around it.
 *
 * @property reason Why the device was skipped — the `failureReason` of the
 *   [RouteFallback][link.socket.ampere.agents.domain.event.RoutingEvent.RouteFallback].
 * @property servedBy The model that took the call instead.
 * @property at When the relay made that decision.
 */
data class OnDeviceFallback(
    val reason: String,
    val servedBy: String,
    val at: Instant,
)

/**
 * The read model behind "is the on-device model being used right now?" (AMPR-327).
 *
 * Built by [OnDeviceInferenceProjection] from events the system already emits —
 * no event exists only to feed it. Everything a surface needs is a property of
 * this one value: whether a call is [in flight][isInUse], which model is
 * serving it, whether the device could serve one at all, and how the work has
 * split between the device and the cloud so far.
 *
 * It describes one process's view since the projection started. It is not
 * persisted; the events it folds are, so it can always be rebuilt with
 * [OnDeviceInferenceProjection.replay].
 *
 * @property availability What is known about the engine, as of [availabilityAsOf].
 * @property availabilityAsOf The timestamp of the fact [availability] rests on.
 * @property servedOnDevice Calls the device completed successfully.
 * @property failedOnDevice Calls the device was given and did not complete.
 * @property servedInCloud Calls a cloud provider completed successfully.
 * @property lastCall The on-device call that finished most recently.
 * @property lastFallback The most recent time the relay routed around the device.
 */
data class OnDeviceInferenceState(
    val availability: OnDeviceAvailability = OnDeviceAvailability.Unknown,
    val availabilityAsOf: Instant? = null,
    val servedOnDevice: Int = 0,
    val failedOnDevice: Int = 0,
    val servedInCloud: Int = 0,
    val lastCall: OnDeviceCallSummary? = null,
    val lastFallback: OnDeviceFallback? = null,
    /**
     * Starts minus completions per call identity, for identities where the two
     * differ. A signed balance rather than a set of open calls because the bus
     * dispatches each handler on its own coroutine: a completion can be folded
     * before the start it answers, which leaves the balance at `-1` until the
     * start arrives and cancels it. Counting is the same whichever comes first.
     */
    internal val ledger: Map<OnDeviceCallKey, Int> = emptyMap(),
) {
    /** On-device calls that have started and not yet completed, one entry per call. */
    val inFlight: List<OnDeviceCallKey>
        get() = ledger.flatMap { (key, balance) -> List(balance.coerceAtLeast(0)) { key } }

    /** Whether the on-device model is generating at this moment. */
    val isInUse: Boolean
        get() = ledger.values.any { it > 0 }

    /**
     * The on-device model to name on a surface: the one generating now, else
     * the one that last generated, else the one the engine says it would use.
     */
    val modelId: String?
        get() = ledger.entries.firstOrNull { it.value > 0 }?.key?.modelId
            ?: lastCall?.call?.modelId
            ?: (availability as? OnDeviceAvailability.Available)?.modelId

    /**
     * The share of successfully completed calls that stayed on the device, in
     * `0.0..1.0`, or null before any call has completed.
     */
    val onDeviceShare: Double?
        get() {
            val total = servedOnDevice + servedInCloud
            return if (total == 0) null else servedOnDevice.toDouble() / total
        }
}
