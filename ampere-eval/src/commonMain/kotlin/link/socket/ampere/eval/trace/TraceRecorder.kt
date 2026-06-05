package link.socket.ampere.eval.trace

import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventRegistry
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.data.DEFAULT_JSON

/**
 * Captures a run's `EventSerialBus` stream into a [Trace].
 *
 * A recorded trajectory *is* the captured bus stream — there is no parallel
 * recording channel. The recorder subscribes to every known event type
 * ([EventRegistry.allEventTypes], the same source of truth the live relay uses),
 * buffers events in emission order for the recording window, and on stop builds
 * and persists a [Trace] via [TraceService].
 *
 * Scoping note (see RECON-trace.md §3): the base `Event` does not carry a
 * `runId`, so events are scoped to a run by the *recording window* (start→stop),
 * and the supplied `runId` / `arcId` are stamped onto the resulting [Trace] as
 * metadata rather than used to filter individual events.
 *
 * @param bus the `EventSerialBus` to capture from (AMPR-183 calls this `EventSerializerBus`).
 * @param traceService persistence boundary the built [Trace] is saved through on stop.
 */
class TraceRecorder(
    private val bus: EventSerialBus,
    private val traceService: TraceService,
    private val json: Json = DEFAULT_JSON,
    private val eventTypes: List<EventType> = EventRegistry.allEventTypes,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val idGenerator: () -> String = { generateUUID() },
) {
    /**
     * Begin recording. Subscribes to the bus immediately; events published after
     * this call are captured until [RecordingHandle.stop].
     */
    fun start(runId: String, arcId: String): RecordingHandle {
        val buffer = Channel<Event>(Channel.UNLIMITED)
        val agentId = "trace-recorder/$runId/${idGenerator()}"

        eventTypes.forEach { eventType ->
            bus.subscribe(
                agentId = agentId,
                eventType = eventType,
                handler = EventHandler<Event, Subscription> { event, _ ->
                    buffer.trySend(event)
                },
            )
        }

        return RecordingHandle(
            traceId = idGenerator(),
            runId = runId,
            arcId = arcId,
            createdAt = clockMillis(),
            buffer = buffer,
            subscribedTypes = eventTypes,
            bus = bus,
            traceService = traceService,
            json = json,
        )
    }
}

/**
 * Handle to an in-progress recording. Call [stop] exactly once to finalize.
 */
class RecordingHandle internal constructor(
    private val traceId: String,
    private val runId: String,
    private val arcId: String,
    private val createdAt: Long,
    private val buffer: Channel<Event>,
    private val subscribedTypes: List<EventType>,
    private val bus: EventSerialBus,
    private val traceService: TraceService,
    private val json: Json,
) {
    /**
     * Stop recording, build the [Trace] from buffered events (in emission order),
     * persist it via the [TraceService], and return it.
     *
     * Returns failure if persistence fails. Idempotent only insofar as the
     * channel is drained once; call exactly once.
     */
    suspend fun stop(): Result<Trace> {
        // Stop receiving new events. NOTE: EventSerialBus.unsubscribe removes ALL
        // handlers for a type (see RECON-trace.md §1) — fine for eval-controlled
        // runs where the recorder is the sole subscriber.
        subscribedTypes.forEach { bus.unsubscribe(it) }
        buffer.close()

        // Dedup by eventId: an event with parentEventTypes is dispatched to more
        // than one subscribed type (see RECON-trace.md §1/§2), so the recorder may
        // see it once per matching type. Keep the first arrival, preserving order.
        val seenEventIds = mutableSetOf<String>()
        val captured = buildList {
            while (true) {
                val event = buffer.tryReceive().getOrNull() ?: break
                if (seenEventIds.add(event.eventId)) add(event)
            }
        }

        val events = captured.mapIndexed { index, event ->
            TraceEvent(
                index = index,
                timestamp = event.timestamp.toEpochMilliseconds(),
                type = event.eventType,
                payload = json.encodeToJsonElement(Event.serializer(), event),
            )
        }

        val trace = Trace(
            id = traceId,
            runId = runId,
            arcId = arcId,
            createdAt = createdAt,
            events = events,
        )

        return traceService.save(trace).map { trace }
    }
}
