package link.socket.ampere.agents.domain.routing.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * The live [OnDeviceInferenceState] for one process — what a surface collects
 * to show when the on-device model is being used (AMPR-327).
 *
 * It holds no logic of its own: every change is a fold of
 * [OnDeviceInferenceProjection]. What it adds is the place the state lives and
 * the two ways facts reach it:
 *
 * - [follow] subscribes to a bus, so calls made anywhere on that bus show up
 *   without the caller knowing a surface exists.
 * - [probe] asks an engine about itself. Nothing on the bus says the device
 *   *can* serve until a call has been routed, so a surface that wants to show
 *   "ready" or "unavailable, and why" before the first call probes on launch
 *   and again whenever it cares to refresh.
 *
 * ```kotlin
 * val monitor = OnDeviceInferenceMonitor(classifier = dispatchingClient)
 * scope.launch { monitor.follow(bus) }
 * scope.launch { monitor.probe(engine) }
 * monitor.state.collect { render(it) }
 * ```
 *
 * @param classifier Decides which calls count as on-device. Pass the
 *   [DispatchingUpstreamLlmClient][link.socket.ampere.llm.DispatchingUpstreamLlmClient]
 *   that executes the calls where there is one — it knows whether an engine is
 *   actually bound — and a [CatalogLocalityClassifier] where there is not.
 * @param clock Stamps [probe] results, so a probe and an event can be ordered.
 */
class OnDeviceInferenceMonitor(
    private val classifier: InferenceLocalityClassifier,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(OnDeviceInferenceState())

    /** The current read model. Conflated: a collector sees the latest state, not every step. */
    val state: StateFlow<OnDeviceInferenceState> = _state.asStateFlow()

    /** Fold one [event]. Events the projection does not read are ignored. */
    suspend fun record(event: Event) {
        val locality = OnDeviceInferenceProjection.classify(event, classifier) ?: return
        _state.update { OnDeviceInferenceProjection.fold(it, event, locality) }
    }

    /** Fold a [capacity] snapshot taken now. */
    fun record(capacity: LocalCapacity) {
        val observedAt = clock.now()
        _state.update { OnDeviceInferenceProjection.fold(it, capacity, observedAt) }
    }

    /**
     * Ask [engine] whether it can serve, fold the answer, and return it.
     *
     * An engine whose probe throws is reported unavailable with the failure as
     * the reason: a surface asking "can the device serve?" should get "no, and
     * here is why" rather than an exception to handle.
     */
    suspend fun probe(engine: LocalInferenceEngine): LocalCapacity {
        val capacity = try {
            engine.probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            LocalCapacity(
                available = false,
                reason = failure.message ?: failure::class.simpleName ?: PROBE_FAILED_REASON,
            )
        }
        record(capacity)
        return capacity
    }

    /**
     * Fold every model call and routing fallback dispatched on [bus] until the
     * calling coroutine is cancelled. Never returns normally.
     *
     * The subscriptions are registered before this first suspends for good, and
     * released on cancellation, so launching it on a scope and cancelling that
     * scope is the whole lifecycle.
     */
    suspend fun follow(bus: EventSerialBus): Nothing {
        val subscriberId = "on-device-inference-monitor-${generateUUID()}"
        val handler = EventHandler<Event, Subscription> { event, _ -> record(event) }
        val subscriptions = mutableListOf<Subscription>()
        try {
            for (eventType in FOLLOWED_EVENT_TYPES) {
                subscriptions += bus.subscribeSuspending(
                    agentId = subscriberId,
                    eventType = eventType,
                    handler = handler,
                )
            }
            awaitCancellation()
        } finally {
            // Cancellation is the normal exit, and releasing a subscription takes the bus
            // mutex — which a cancelled coroutine cannot do.
            withContext(NonCancellable) {
                subscriptions.forEach { bus.unsubscribeSuspending(it) }
            }
        }
    }

    companion object {
        /** The events [follow] subscribes to — exactly what [OnDeviceInferenceProjection] reads. */
        val FOLLOWED_EVENT_TYPES: List<EventType> = listOf(
            ProviderCallStartedEvent.EVENT_TYPE,
            ProviderCallCompletedEvent.EVENT_TYPE,
            RoutingEvent.RouteFallback.EVENT_TYPE,
        )

        /** Reason recorded when a probe throws without saying why. */
        const val PROBE_FAILED_REASON: String = "local_capacity_probe_failed"
    }
}
