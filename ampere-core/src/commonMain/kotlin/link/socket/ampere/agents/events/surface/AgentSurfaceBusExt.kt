package link.socket.ampere.agents.events.surface

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.AgentSurfaceEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Emit an [AgentSurfaceEvent.Requested] for [surface] over [this] bus.
 */
suspend fun EventSerialBus.emitSurfaceRequest(
    surface: AgentSurface,
    eventSource: EventSource,
    urgency: Urgency = Urgency.MEDIUM,
) {
    publish(
        AgentSurfaceEvent.Requested(
            eventId = generateUUID(surface.correlationId),
            timestamp = Clock.System.now(),
            eventSource = eventSource,
            urgency = urgency,
            surface = surface,
        ),
    )
}

/**
 * Subscribe to [AgentSurfaceEvent.Responded] events and suspend until one is
 * delivered with the matching [correlationId].
 *
 * If [timeout] is supplied and elapses first, an
 * [AgentSurfaceResponse.TimedOut] is returned instead of throwing. Callers
 * that prefer cancellation-style timeouts can pass [timeout] = null and wrap
 * with [withTimeout] themselves.
 *
 * Note: due to the current bus contract, multiple concurrent awaits for the
 * same event type share a registration; this helper completes only on the
 * first match for [correlationId] and ignores other events. Callers should
 * not assume the underlying handler is unregistered when this function
 * returns.
 *
 * Registers through [EventSerialBus.subscribeSuspending]: the blocking
 * [EventSerialBus.subscribe] nests a `runBlocking` inside the caller's
 * coroutine, which on an event-loop thread re-entrantly runs whatever is
 * already queued — including the responder's handler — before the awaiter
 * is registered, so a response could be dispatched to nobody.
 */
suspend fun EventSerialBus.awaitSurfaceResponse(
    awaiterAgentId: AgentId,
    correlationId: CorrelationId,
    timeout: Duration? = null,
): AgentSurfaceResponse {
    val deferred = CompletableDeferred<AgentSurfaceResponse>()

    subscribeSuspending(
        agentId = awaiterAgentId,
        eventType = AgentSurfaceEvent.Responded.EVENT_TYPE,
        handler = EventHandler { event, _ ->
            val responded = event as AgentSurfaceEvent.Responded
            if (responded.correlationId == correlationId && !deferred.isCompleted) {
                deferred.complete(responded.response)
            }
        },
    )

    return if (timeout == null) {
        deferred.await()
    } else {
        try {
            withTimeout(timeout) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            AgentSurfaceResponse.TimedOut(
                correlationId = correlationId,
                timeoutMillis = timeout.inWholeMilliseconds,
            )
        }
    }
}
