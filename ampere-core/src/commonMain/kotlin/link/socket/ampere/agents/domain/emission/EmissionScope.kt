package link.socket.ampere.agents.domain.emission

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.reasoning.Confidence
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.util.randomUUID

/**
 * DSL scope for authoring, publishing, and (where applicable) awaiting
 * replies to [Emission]s.
 *
 * Obtain via [emission]. Builders available:
 * - [ask] — a prompt-and-affordances [EmissionKind.Decision], or a pre-built [Emission]
 * - [askHuman] — human-interaction Emissions, produces [HumanInteractionEvent.InputRequested]
 * - [confirm] — an [EmissionKind.Confirmation] gating an effect
 * - [emit] — fire-and-forget [EmissionKind.Prose] narration
 * - [sense] — fire-and-forget [EmissionKind.Sensor] readings
 *
 * [ask], [askHuman], and [confirm] publish, register a reply waiter in
 * [replyRegistry], and suspend until the matching [EmissionEvent.Resolved]
 * arrives or [EmissionTimeout] fires. [emit] and [sense] publish and return
 * immediately — narration and ambient readings have no reply to await.
 *
 * [publish] is the seam a host uses to intercept or wrap outgoing events —
 * for example to carry [Emission.surfaces] to its own renderers — before
 * they reach the door. A failure inside it propagates out of the builder
 * that triggered it; nothing here swallows a failed publish.
 *
 * Every timestamp this scope stamps comes from [clock] — the door's clock
 * when built through [emission] — so a pinned clock pins the Emissions too.
 */
class EmissionScope(
    private val eventSource: EventSource,
    private val replyRegistry: EmissionReplyRegistry,
    private val publish: suspend (Event) -> Unit,
    private val clock: Clock,
    /**
     * Ambient Arc-run identity (AMPR-240), used to populate
     * [EmissionProvenance.runId] on every Emission built by this scope that
     * doesn't supply its own `provenance`. Null preserves digest-only
     * provenance for callers outside an Arc run.
     */
    private val runId: RunId? = null,
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
        publish(
            EmissionEvent.BaseProduced(
                eventId = randomUUID(),
                timestamp = clock.now(),
                eventSource = eventSource,
                emission = emission,
            ),
        )
        return replyRegistry.awaitReply(emission.id, timeout)
            ?: throw EmissionTimeout(emission.id, timeout)
    }

    /**
     * Author and publish an [EmissionKind.Decision] Emission from [prompt] and
     * [affordances], suspending until the matching reply arrives.
     *
     * This is the authoring-level counterpart to `ask(emission)` — callers
     * describe intent rather than assembling an [Emission] by hand.
     *
     * @throws EmissionTimeout if no reply arrives within [timeout]
     */
    suspend fun ask(
        prompt: String,
        context: String? = null,
        affordances: AffordanceBuilder.() -> Unit = { freeTextAffordance("Provide response") },
        surfaces: List<Surface> = emptyList(),
        confidence: Confidence? = null,
        provenance: EmissionProvenance? = null,
        dedupKey: String? = null,
        timeout: Duration = 30.minutes,
    ): EmissionEvent.Resolved {
        val payload = EmissionPayload.Decision(prompt = prompt, context = context)
        val emission = Emission(
            id = randomUUID(),
            kind = EmissionKind.Decision,
            payload = payload,
            affordances = AffordanceBuilder().apply(affordances).build(),
            confidence = confidence,
            provenance = provenance ?: defaultProvenance(payload),
            dedupKey = dedupKey,
            producedAt = clock.now(),
            surfaces = surfaces,
        )
        return ask(emission, timeout)
    }

    /**
     * Author and publish an [EmissionKind.Confirmation] Emission gating [action],
     * suspending until the human confirms or declines.
     *
     * Defaults to a `Confirm` / `Cancel` affordance pair. [dedupKey] defaults
     * to [Emission.computeDedupKey] — a content digest of the payload, since
     * Confirmation is an effect-bearing kind.
     *
     * @throws EmissionTimeout if no reply arrives within [timeout]
     */
    suspend fun confirm(
        action: String,
        dangerLevel: DangerLevel,
        preview: String? = null,
        affordances: AffordanceBuilder.() -> Unit = {
            affordance("Confirm")
            affordance("Cancel")
        },
        surfaces: List<Surface> = emptyList(),
        provenance: EmissionProvenance? = null,
        dedupKey: String? = null,
        timeout: Duration = 30.minutes,
    ): EmissionEvent.Resolved {
        val payload = EmissionPayload.Confirmation(action = action, preview = preview, dangerLevel = dangerLevel)
        val emission = Emission(
            id = randomUUID(),
            kind = EmissionKind.Confirmation,
            payload = payload,
            affordances = AffordanceBuilder().apply(affordances).build(),
            provenance = provenance ?: defaultProvenance(payload),
            dedupKey = null,
            producedAt = clock.now(),
            surfaces = surfaces,
        )
        return ask(emission.copy(dedupKey = dedupKey ?: emission.computeDedupKey()), timeout)
    }

    /**
     * Author and publish an [EmissionKind.Prose] Emission. Fire-and-forget —
     * narration has no affordances and no reply is awaited.
     */
    suspend fun emit(
        text: String,
        format: ProseFormat = ProseFormat.PLAIN,
        surfaces: List<Surface> = emptyList(),
        provenance: EmissionProvenance? = null,
    ): Emission {
        val payload = EmissionPayload.Prose(text = text, format = format)
        return publishOnly(
            Emission(
                id = randomUUID(),
                kind = EmissionKind.Prose,
                payload = payload,
                provenance = provenance ?: defaultProvenance(payload),
                producedAt = clock.now(),
                surfaces = surfaces,
            ),
        )
    }

    /**
     * Author and publish an [EmissionKind.Sensor] Emission. Fire-and-forget —
     * ambient readings have no affordances and no reply is awaited.
     */
    suspend fun sense(
        label: String,
        value: String,
        unit: String? = null,
        refreshUri: String? = null,
        surfaces: List<Surface> = emptyList(),
        provenance: EmissionProvenance? = null,
    ): Emission {
        val payload = EmissionPayload.Sensor(label = label, value = value, unit = unit, refreshUri = refreshUri)
        return publishOnly(
            Emission(
                id = randomUUID(),
                kind = EmissionKind.Sensor,
                payload = payload,
                provenance = provenance ?: defaultProvenance(payload),
                producedAt = clock.now(),
                surfaces = surfaces,
            ),
        )
    }

    private suspend fun publishOnly(emission: Emission): Emission {
        publish(
            EmissionEvent.BaseProduced(
                eventId = randomUUID(),
                timestamp = clock.now(),
                eventSource = eventSource,
                emission = emission,
            ),
        )
        return emission
    }

    /** Provenance for callers that don't supply their own — carries the ambient [runId], if any. */
    private fun defaultProvenance(payload: EmissionPayload): EmissionProvenance =
        EmissionProvenance(runId = runId, inputDigest = inputDigest(payload))

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
     * @param surfaces Ordered surface-delivery intent; see [Emission.surfaces]
     * @param provenance Attribution for this Emission; defaults to digest-only (no run/workflow/tool
     *   attribution) when the caller doesn't have richer context to supply
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
        surfaces: List<Surface> = emptyList(),
        provenance: EmissionProvenance? = null,
        urgency: Urgency = Urgency.HIGH,
        timeout: Duration = 30.minutes,
        onProduced: suspend (HumanInteractionEvent.InputRequested) -> Unit = {},
    ): EmissionEvent.Resolved {
        val affordanceList = AffordanceBuilder().apply(affordances).build()
        val payload = EmissionPayload.Decision(prompt = prompt, context = context)
        val emission = Emission(
            id = randomUUID(),
            kind = EmissionKind.Decision,
            payload = payload,
            affordances = affordanceList,
            confidence = null,
            provenance = provenance ?: defaultProvenance(payload),
            dedupKey = null,
            producedAt = clock.now(),
            surfaces = surfaces,
        )

        val requestId = randomUUID()
        val inputRequested = HumanInteractionEvent.InputRequested(
            eventId = randomUUID(),
            timestamp = clock.now(),
            eventSource = eventSource,
            urgency = urgency,
            emission = emission,
            requestId = requestId,
            agentId = agentId,
            ticketId = ticketId,
            taskId = taskId,
        )

        publish(inputRequested)
        onProduced(inputRequested)

        val reply = replyRegistry.awaitReply(emission.id, timeout)
        if (reply == null) {
            publish(
                HumanInteractionEvent.RequestTimedOut(
                    eventId = randomUUID(),
                    timestamp = clock.now(),
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
 * Run [block] inside an [EmissionScope] that publishes through the door [eventApi]
 * and awaits replies via [replyRegistry].
 *
 * Every event the scope produces goes through [AgentEventApi.publish] — persisted to the
 * `EventStore` inside its envelope (tagged with [runId]) and only then dispatched on the
 * bus — so a fold over the store sees what a live subscriber sees (F1). A publish failure
 * is not swallowed: it propagates out of the builder that produced the event, and so out
 * of [block].
 *
 * For the duration of [block], one reply-delivery subscriber on [EmissionEvent.Resolved.EVENT_TYPE]
 * forwards incoming replies to [replyRegistry], resuming any suspended [EmissionScope.ask] /
 * [EmissionScope.askHuman] / [EmissionScope.confirm] calls. [HumanInteractionEvent.InputProvided]
 * lists that type among its parents, so the same subscriber receives it. The subscriber is registered
 * on [eventApi]'s bus and released when [block] returns, throws, or is cancelled, leaving the bus's
 * handler count where it was.
 *
 * @param replyRegistry Defaults to [GlobalEmissionReplyRegistry], which is shared by everything in
 *   the process — see its warning before relying on the default outside tests.
 * @param runId Ambient Arc-run identity: stamped onto every Emission's default provenance and onto
 *   the envelope of every event published through the door.
 * @param publish Seam for a host to intercept or wrap outgoing events before they reach the door —
 *   see [EmissionScope]. Defaults to `eventApi.publish(event, runId = runId)`; a host that wraps
 *   should still forward to the door and return its result, so the event is persisted.
 */
suspend fun <T> emission(
    eventSource: EventSource,
    eventApi: AgentEventApi,
    replyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    runId: RunId? = null,
    publish: suspend (Event) -> Result<StoredEvent> = { eventApi.publish(it, runId = runId) },
    block: suspend EmissionScope.() -> T,
): T = withReplyRouter(eventApi.eventSerialBus, replyRegistry) {
    EmissionScope(
        eventSource = eventSource,
        replyRegistry = replyRegistry,
        publish = { event -> publish(event).getOrThrow() },
        clock = eventApi.clock,
        runId = runId,
    ).block()
}

/**
 * Bus-only variant of [emission] kept for the publishers set (b) of F1 has not migrated yet
 * (`AgentMessageApi`, AMPR-338). Events produced here are dispatched live but **never
 * persisted**, which is the C2 defect F1 removes; nothing new should call this.
 *
 * Removed by the F1 lock (AMPR-340), when `EventSerialBus.publish` goes internal.
 */
@Deprecated(
    message = "Bypasses the EventStore: events are dispatched but never persisted (F1/C2). " +
        "Pass an AgentEventApi instead. Kept only until AMPR-338 migrates AgentMessageApi; " +
        "removed by the AMPR-340 lock.",
    replaceWith = ReplaceWith("emission(eventSource, eventApi, replyRegistry, runId, block = block)"),
)
suspend fun <T> emission(
    eventSource: EventSource,
    eventSerialBus: EventSerialBus,
    replyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    runId: RunId? = null,
    block: suspend EmissionScope.() -> T,
): T = withReplyRouter(eventSerialBus, replyRegistry) {
    EmissionScope(
        eventSource = eventSource,
        replyRegistry = replyRegistry,
        publish = { event -> eventSerialBus.publish(event) },
        clock = Clock.System,
        runId = runId,
    ).block()
}

/**
 * Holds the single reply-router subscription on [bus] for the duration of [block] and releases
 * it on every exit path (AMPR-332). Shared by both [emission] overloads so the subscribe /
 * unsubscribe behaviour is identical whichever door the scope publishes through.
 */
private suspend fun <T> withReplyRouter(
    bus: EventSerialBus,
    replyRegistry: EmissionReplyRegistry,
    block: suspend () -> T,
): T {
    val subscription = bus.subscribeSuspending(
        agentId = "emission-reply-router",
        eventType = EmissionEvent.Resolved.EVENT_TYPE,
        handler = EventHandler { event, _ ->
            (event as? EmissionEvent.Resolved)?.let(replyRegistry::deliver)
        },
    )

    return try {
        block()
    } finally {
        // Cancellation is a normal exit (e.g. the caller gave up on a reply), and releasing the
        // subscription needs the bus mutex — which a cancelled coroutine cannot take.
        withContext(NonCancellable) {
            bus.unsubscribeSuspending(subscription)
        }
    }
}
