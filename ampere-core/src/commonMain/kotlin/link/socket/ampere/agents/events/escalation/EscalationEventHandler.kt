package link.socket.ampere.agents.events.escalation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Listens for `MessageEvent.EscalationRequested` and notifies a human.
 * Uses AgentMessageApi to read the thread for any additional checks.
 *
 * Can be used in two ways:
 * 1. Direct invocation via [invoke] (when wired through MessageRouter)
 * 2. Self-subscription via [start] (standalone mode with EventBus)
 */
class EscalationEventHandler(
    private val coroutineScope: CoroutineScope,
    private val humanNotifier: Notifier.Human,
    private val eventSerialBus: EventSerialBus,
    private val agentId: AgentId = "escalation-handler",
) : EventHandler<MessageEvent.EscalationRequested, Subscription>() {

    /**
     * Start the escalation handler by subscribing to EscalationRequested events.
     * This enables standalone mode where the handler self-subscribes to the EventBus.
     *
     * @throws IllegalStateException if eventBus or scope were not provided in constructor
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

    // Subscribe to EscalationRequested events and send them to Human
    override suspend fun invoke(
        event: MessageEvent.EscalationRequested,
        subscription: Subscription?,
    ) {
        super.invoke(event, subscription)
        notifyHumanEscalation(event)
    }

    private suspend fun notifyHumanEscalation(
        event: MessageEvent.EscalationRequested,
    ) {
        // By design, status should already be WAITING_FOR_HUMAN after escalateToHuman()
        // We rely on AgentMessageApi to enforce the transition; here we just notify.
        humanNotifier.notifyEscalation(
            threadId = event.threadId,
            agentId = event.eventSource.getIdentifier(),
            reason = event.reason,
            context = event.context,
        )
    }
}
