package link.socket.ampere.agents.events.relay

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * How many Emissions a single [emissions] collector buffers before the oldest one is dropped.
 *
 * Sized for a progress surface that renders a handful of Emissions per second and can stall for
 * a second or two — a Live Activity being redrawn, a Swift `AsyncStream` consumer waiting on the
 * main actor. Anything past that is a consumer that has fallen too far behind to be showing
 * "progress" at all, so the stream keeps the newest state and reports the loss.
 */
const val DEFAULT_EMISSION_BUFFER_CAPACITY: Int = 64

/**
 * A live stream of the Emissions produced by one Arc run.
 *
 * The [EventSerialBus] is callback-only: it hands a handler to every subscriber and never
 * suspends the publisher. This adapts that into a cold [Flow] with a real backpressure policy.
 *
 * Three properties make it safe to hand to a second consumer, which the one pre-existing
 * bus→Flow adapter ([EventRelayServiceImpl.subscribeToLiveEvents]) is not:
 *
 * 1. **Per-collector teardown.** Each collector registers its own subscription and releases it
 *    with [EventSerialBus.unsubscribe], so cancelling one collector cannot silence another —
 *    including the emission reply routers the Arc itself depends on.
 * 2. **Explicit overflow.** A slow collector overflows into [BufferOverflow.DROP_OLDEST] rather
 *    than the silent drop of a default-capacity `trySend`. The newest state survives, which is
 *    what a progress surface wants.
 * 3. **Loss is reported, not swallowed.** Every dropped Emission calls [onDropped] with the
 *    running total for this collector, so a consumer can say "12 updates skipped" instead of
 *    quietly rendering a stale timeline. Keep [onDropped] cheap — it runs on the producing
 *    coroutine, inside the channel's own bookkeeping.
 *
 * Ordering is the bus's: handlers are dispatched as independent coroutines, so two Emissions
 * published concurrently can arrive in either order. Emissions published *before* collection
 * starts are not replayed — the bus has no history. Collect before starting the run, or use
 * [link.socket.ampere.domain.arc.bridge.ArcSession.start], which subscribes before it launches.
 *
 * @param runId Keep only Emissions whose `provenance.runId` matches. Null streams every
 *   Emission on the bus, whatever run produced it.
 * @param capacity Buffered Emissions per collector; see [DEFAULT_EMISSION_BUFFER_CAPACITY].
 * @param onDropped Called with the running number of Emissions this collector has lost.
 */
fun EventSerialBus.emissions(
    runId: RunId? = null,
    capacity: Int = DEFAULT_EMISSION_BUFFER_CAPACITY,
    onDropped: (droppedTotal: Long) -> Unit = {},
): Flow<Emission> = flow {
    // StateFlow rather than a plain var: the counter is written from whichever bus coroutine
    // overflowed the buffer and read from the collector, and `update` is the one atomic
    // read-modify-write available in commonMain without a new dependency.
    val droppedTotal = MutableStateFlow(0L)

    val channel = Channel<Emission>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { onDropped(droppedTotal.updateAndGet { it + 1 }) },
    )

    val subscription = subscribeSuspending(
        agentId = "emission-stream-${generateUUID()}",
        eventType = EmissionEvent.Produced.EVENT_TYPE,
        handler = EventHandler { event, _ ->
            // Produced is polymorphic: BaseProduced and HumanInteractionEvent.InputRequested
            // both land here. Anything else on this type is not ours to read.
            val emission = (event as? EmissionEvent.Produced)?.emission
            if (emission != null && (runId == null || emission.provenance.runId == runId)) {
                channel.trySend(emission)
            }
        },
    )

    try {
        for (emission in channel) {
            emit(emission)
        }
    } finally {
        // Cancellation is the normal exit here, and releasing the subscription needs the bus
        // mutex — which a cancelled coroutine cannot take.
        withContext(NonCancellable) {
            unsubscribeSuspending(subscription)
        }
        channel.close()
    }
}
