package link.socket.ampere.agents.events.escalation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Listens for [MessageEvent.EscalationRequested] and performs thread-observation
 * side effects.
 *
 * Notification routing (the `humanNotifier` invocation) has been removed: channel
 * selection is now [Surface][link.socket.ampere.agents.domain.emission.Surface]
 * policy, handled by the Emission DSL inside
 * [link.socket.ampere.agents.events.messages.AgentMessageApi.escalateToHuman].
 * This handler is retained for any thread-observation work that is not Emission-
 * related (event logging, metrics, etc.).
 *
 * Can be used in two ways:
 * 1. Direct invocation via [invoke] (when wired through MessageRouter).
 * 2. Self-subscription via [start] (standalone mode with EventBus).
 */
class EscalationEventHandler(
    private val coroutineScope: CoroutineScope,
    private val eventSerialBus: EventSerialBus,
    private val agentId: AgentId = "escalation-handler",
) : EventHandler<MessageEvent.EscalationRequested, Subscription>() {

    /**
     * Start the escalation handler by subscribing to EscalationRequested events.
     */
    fun start() {
        coroutineScope.launch {
            eventSerialBus.subscribe(
                agentId = agentId,
                eventType = MessageEvent.EscalationRequested.EVENT_TYPE,
                handler = EventHandler { event, subscription ->
                    val escalationEvent = event as MessageEvent.EscalationRequested
                    invoke(escalationEvent, subscription)
                },
            )
        }
    }

    override suspend fun invoke(
        event: MessageEvent.EscalationRequested,
        subscription: Subscription?,
    ) {
        super.invoke(event, subscription)
        // Notification routing is now handled by the Emission DSL's SurfacePolicy.
        // Add thread-observation side effects here if needed (metrics, tracing, etc.).
    }
}
