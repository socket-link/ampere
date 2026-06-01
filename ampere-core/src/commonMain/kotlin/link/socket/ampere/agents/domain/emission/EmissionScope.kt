package link.socket.ampere.agents.domain.emission

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.util.randomUUID

/**
 * DSL scope for emitting [Emission]s and awaiting replies.
 *
 * Obtain via [emission]. Two builders are available:
 * - [ask] — generic Emissions, produces [EmissionEvent.BaseProduced]
 * - [askHuman] — human-interaction Emissions, produces [HumanInteractionEvent.InputRequested]
 *
 * Both builders publish, register a reply waiter in [replyRegistry], and
 * suspend until the matching [EmissionEvent.Resolved] arrives or
 * [EmissionTimeout] fires.
 */
class EmissionScope(
    private val eventSource: EventSource,
    private val eventSerialBus: EventSerialBus,
    private val replyRegistry: EmissionReplyRegistry,
) {

    /**
     * Publish a generic [Emission] and suspend until the matching reply arrives.
     *
     * @throws EmissionTimeout if no reply arrives within [timeout]
     */
    suspend fun ask(
        emission: Emission,
        timeout: Duration = 30.minutes,
    ): EmissionEvent.Resolved {
        eventSerialBus.publish(
            EmissionEvent.BaseProduced(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = eventSource,
                emission = emission,
            ),
        )
        return replyRegistry.awaitReply(emission.id, timeout)
            ?: throw EmissionTimeout(emission.id, timeout)
    }

    /**
     * Publish a human-interaction [Emission] and suspend until the reply arrives.
     *
     * Unlike [ask], this produces a [HumanInteractionEvent.InputRequested] that
     * carries human-specific attribution fields. On timeout, publishes
     * [HumanInteractionEvent.RequestTimedOut] before throwing [EmissionTimeout].
     *
     * @param prompt The question to present to the human
     * @param agentId The agent making this request
     * @param context Optional context string shown alongside the prompt
     * @param ticketId Optional ticket attribution (preserved in the event)
     * @param taskId Optional task attribution (preserved in the event)
     * @param affordances Builder for response options; defaults to a single free-text affordance
     * @param urgency Urgency of this request (drives surface-priority defaults)
     * @param timeout How long to wait before timing out
     * @param onProduced Called synchronously after the event is published but before suspension;
     *   use this for side-effects such as printing a console banner
     * @throws EmissionTimeout if no reply arrives within [timeout]
     */
    suspend fun askHuman(
        prompt: String,
        agentId: AgentId,
        context: String? = null,
        ticketId: String? = null,
        taskId: String? = null,
        affordances: AffordanceBuilder.() -> Unit = { freeTextAffordance("Provide response") },
        urgency: Urgency = Urgency.HIGH,
        timeout: Duration = 30.minutes,
        onProduced: suspend (HumanInteractionEvent.InputRequested) -> Unit = {},
    ): EmissionEvent.Resolved {
        val affordanceList = AffordanceBuilder().apply(affordances).build()
        val payload = EmissionPayload.Decision(prompt = prompt, context = context)
        val provenance = EmissionProvenance(
            runId = null,
            workflowId = null,
            sourceEventId = null,
            toolInvocationId = null,
            pluginId = null,
            modelId = null,
            inputDigest = inputDigest(payload),
        )
        val emission = Emission(
            id = randomUUID(),
            kind = EmissionKind.Decision,
            payload = payload,
            affordances = affordanceList,
            confidence = null,
            provenance = provenance,
            dedupKey = null,
            producedAt = Clock.System.now(),
        )

        val requestId = randomUUID()
        val inputRequested = HumanInteractionEvent.InputRequested(
            eventId = randomUUID(),
            timestamp = Clock.System.now(),
            eventSource = eventSource,
            urgency = urgency,
            emission = emission,
            requestId = requestId,
            agentId = agentId,
            ticketId = ticketId,
            taskId = taskId,
        )

        eventSerialBus.publish(inputRequested)
        onProduced(inputRequested)

        val reply = replyRegistry.awaitReply(emission.id, timeout)
        if (reply == null) {
            eventSerialBus.publish(
                HumanInteractionEvent.RequestTimedOut(
                    eventId = randomUUID(),
                    timestamp = Clock.System.now(),
                    eventSource = eventSource,
                    urgency = urgency,
                    emissionId = emission.id,
                    requestId = requestId,
                    agentId = agentId,
                    timeoutMinutes = timeout.inWholeMinutes,
                ),
            )
            throw EmissionTimeout(emission.id, timeout)
        }
        return reply
    }
}

/**
 * Run [block] inside an [EmissionScope] backed by [eventSerialBus] and [replyRegistry].
 *
 * Also wires reply-delivery subscribers so that incoming [EmissionEvent.BaseResolved] and
 * [HumanInteractionEvent.InputProvided] events are forwarded to [replyRegistry], resuming
 * any suspended [EmissionScope.ask] / [EmissionScope.askHuman] calls.
 */
suspend fun <T> emission(
    eventSource: EventSource,
    eventSerialBus: EventSerialBus,
    replyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    block: suspend EmissionScope.() -> T,
): T {
    eventSerialBus.subscribe<EmissionEvent.BaseResolved, EventSubscription.ByEventClassType>(
        agentId = "emission-reply-router",
        eventType = EmissionEvent.Resolved.EVENT_TYPE,
    ) { event, _ ->
        replyRegistry.deliver(event)
    }

    eventSerialBus.subscribe<HumanInteractionEvent.InputProvided, EventSubscription.ByEventClassType>(
        agentId = "emission-reply-router-human",
        eventType = HumanInteractionEvent.InputProvided.EVENT_TYPE,
    ) { event, _ ->
        replyRegistry.deliver(event)
    }

    return EmissionScope(eventSource, eventSerialBus, replyRegistry).block()
}
