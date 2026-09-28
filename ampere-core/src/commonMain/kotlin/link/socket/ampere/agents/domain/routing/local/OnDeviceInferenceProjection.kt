package link.socket.ampere.agents.domain.routing.local

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.event.TelemetryEvent

/**
 * Folds the events Ampere already emits around a model call into an
 * [OnDeviceInferenceState] (AMPR-327).
 *
 * Three facts go in:
 *
 * - [ProviderCallStartedEvent] / [ProviderCallCompletedEvent] — which calls are
 *   in flight and how they ended. Only calls the [InferenceLocalityClassifier]
 *   places [on the device][InferenceLocality.ON_DEVICE] move the in-flight
 *   ledger; a successful cloud completion is counted so a surface can show the
 *   split.
 * - [RoutingEvent.RouteFallback] — the relay wanted the device and routed around
 *   it. This is the only event that says *why* a call did not stay local.
 * - A [LocalCapacity] probe — what the engine says about itself, which is the
 *   only source of availability before any call has been routed.
 *
 * ## Order does not matter
 *
 * `EventSerialBus` launches every handler on its own coroutine, so a completion
 * can reach a subscriber before the start it answers. Each step here is
 * therefore written so that folding the same facts in any order ends in the same
 * state: the ledger only counts, and every "most recent" field keeps the fact
 * with the later timestamp rather than the one folded last. `replay` of a stored
 * run and a live subscription agree for the same reason.
 *
 * Stateless. Hold the state in a
 * [StateFlow][kotlinx.coroutines.flow.StateFlow] ([OnDeviceInferenceMonitor]
 * does) or fold a list with [replay].
 */
object OnDeviceInferenceProjection {

    /**
     * The locality that decides how [event] folds, or null for an event this
     * projection does not read.
     *
     * Split from [fold] because the classifier suspends (its catalog is
     * mutex-guarded) and an atomic state update cannot: a caller classifies
     * first, then folds inside its `update { }`.
     */
    suspend fun classify(
        event: Event,
        classifier: InferenceLocalityClassifier,
    ): InferenceLocality? = when (event) {
        is TelemetryEvent -> classifier.localityOf(event.providerId, event.modelId)
        is RoutingEvent.RouteFallback -> classifier.localityOf(event.failedProvider, event.failedModel)
        else -> null
    }

    /**
     * Fold [event] into [state], given the [locality] that [classify] returned
     * for it. Pure: no suspension, no clock, no I/O.
     */
    fun fold(
        state: OnDeviceInferenceState,
        event: Event,
        locality: InferenceLocality,
    ): OnDeviceInferenceState = when (event) {
        is ProviderCallStartedEvent ->
            when (locality) {
                InferenceLocality.ON_DEVICE -> state.withStart(event.callKey())
                InferenceLocality.CLOUD -> state
            }

        is ProviderCallCompletedEvent ->
            when (locality) {
                InferenceLocality.ON_DEVICE -> state.withOnDeviceCompletion(event)
                InferenceLocality.CLOUD ->
                    if (event.success) state.copy(servedInCloud = state.servedInCloud + 1) else state
            }

        is RoutingEvent.RouteFallback ->
            when (locality) {
                InferenceLocality.ON_DEVICE -> state.withFallback(event)
                InferenceLocality.CLOUD -> state
            }

        else -> state
    }

    /**
     * [classify] then [fold]. Events this projection does not read return
     * [state] unchanged, so a caller may hand it everything on the bus.
     */
    suspend fun fold(
        state: OnDeviceInferenceState,
        event: Event,
        classifier: InferenceLocalityClassifier,
    ): OnDeviceInferenceState {
        val locality = classify(event, classifier) ?: return state
        return fold(state, event, locality)
    }

    /**
     * Fold what the engine reported about itself at [observedAt].
     *
     * A probe older than the fact [state] already rests on is ignored: a slow
     * probe must not overwrite the outcome of a call that finished after it was
     * taken.
     */
    fun fold(
        state: OnDeviceInferenceState,
        capacity: LocalCapacity,
        observedAt: Instant,
    ): OnDeviceInferenceState = state.withAvailability(
        availability = if (capacity.available) {
            OnDeviceAvailability.Available(
                modelId = capacity.modelId,
                maxContextTokens = capacity.maxContextTokens,
            )
        } else {
            OnDeviceAvailability.Unavailable(reason = capacity.reason)
        },
        asOf = observedAt,
    )

    /** Rebuild the state from stored [events], in whatever order the store returned them. */
    suspend fun replay(
        events: Iterable<Event>,
        classifier: InferenceLocalityClassifier,
        initial: OnDeviceInferenceState = OnDeviceInferenceState(),
    ): OnDeviceInferenceState {
        var state = initial
        for (event in events) {
            state = fold(state, event, classifier)
        }
        return state
    }

    private fun TelemetryEvent.callKey(): OnDeviceCallKey = OnDeviceCallKey(
        workflowId = workflowId,
        agentId = agentId,
        providerId = providerId,
        modelId = modelId,
        cognitivePhase = cognitivePhase,
    )

    private fun OnDeviceInferenceState.withStart(key: OnDeviceCallKey): OnDeviceInferenceState =
        copy(ledger = ledger.adjusting(key, by = +1))

    private fun OnDeviceInferenceState.withOnDeviceCompletion(
        event: ProviderCallCompletedEvent,
    ): OnDeviceInferenceState {
        val key = event.callKey()
        val summary = OnDeviceCallSummary(
            call = key,
            completedAt = event.timestamp,
            latencyMs = event.latencyMs,
            success = event.success,
            errorType = event.errorType,
        )
        val counted = copy(
            ledger = ledger.adjusting(key, by = -1),
            servedOnDevice = servedOnDevice + if (event.success) 1 else 0,
            failedOnDevice = failedOnDevice + if (event.success) 0 else 1,
            lastCall = lastCall.takeIf { it != null && it.completedAt > event.timestamp } ?: summary,
        )
        // A completion the engine produced is proof the device could serve at that moment.
        // A failure proves nothing about availability — a guardrail refusal and an
        // over-long prompt fail on a perfectly available model — so it leaves it alone.
        return if (event.success) {
            counted.withAvailability(
                availability = OnDeviceAvailability.Available(
                    modelId = event.modelId,
                    maxContextTokens = (availability as? OnDeviceAvailability.Available)?.maxContextTokens,
                ),
                asOf = event.timestamp,
            )
        } else {
            counted
        }
    }

    private fun OnDeviceInferenceState.withFallback(
        event: RoutingEvent.RouteFallback,
    ): OnDeviceInferenceState {
        val fallback = OnDeviceFallback(
            reason = event.failureReason,
            servedBy = event.fallbackDecision.modelName,
            at = event.timestamp,
        )
        return copy(
            lastFallback = lastFallback.takeIf { it != null && it.at > event.timestamp } ?: fallback,
        ).withAvailability(
            availability = OnDeviceAvailability.Unavailable(reason = event.failureReason),
            asOf = event.timestamp,
        )
    }

    /** Keep whichever availability rests on the later fact; a tie goes to the newcomer. */
    private fun OnDeviceInferenceState.withAvailability(
        availability: OnDeviceAvailability,
        asOf: Instant,
    ): OnDeviceInferenceState {
        val current = availabilityAsOf
        return if (current != null && current > asOf) {
            this
        } else {
            copy(availability = availability, availabilityAsOf = asOf)
        }
    }

    /**
     * Move [key]'s balance [by] one start (`+1`) or one completion (`-1`),
     * dropping the entry once it returns to zero so the ledger holds open calls
     * rather than history.
     */
    private fun Map<OnDeviceCallKey, Int>.adjusting(
        key: OnDeviceCallKey,
        by: Int,
    ): Map<OnDeviceCallKey, Int> {
        val balance = (this[key] ?: 0) + by
        return if (balance == 0) this - key else this + (key to balance)
    }
}
