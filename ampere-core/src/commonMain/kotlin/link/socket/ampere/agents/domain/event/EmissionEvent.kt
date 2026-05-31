package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.AffordanceId
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionId

/**
 * Bus-level events that carry an [Emission].
 *
 * AMPERE publishes [Produced] when the agent decides to surface an
 * Emission. A consumer (today: Socket) publishes [Resolved] when an
 * affordance reply arrives, causally linking the reply back to the
 * originating Emission and the chosen [AffordanceId].
 *
 * Both event types share the `EmissionEvent` discriminator slot in
 * `EventRegistry.allEventTypes`, so consumers subscribe to exactly the
 * lifecycle they care about.
 */
@Serializable
sealed interface EmissionEvent : Event {

    /** The Emission whose lifecycle this event refers to. */
    val emissionId: EmissionId

    /** Published when the agent produces an [Emission]. */
    @Serializable
    @SerialName("EmissionEvent.Produced")
    data class Produced(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
        val emission: Emission,
    ) : EmissionEvent {

        override val emissionId: EmissionId = emission.id

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Emission produced: ")
            append(emission.kind::class.simpleName ?: "Unknown")
            append(" (id=${emission.id})")
            emission.dedupKey?.let { append(" dedup=$it") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "EmissionProduced"
        }
    }

    /**
     * Published when an affordance reply arrives. `replyContext` carries
     * the (opaque to AMPERE) `signalPayload` of the chosen affordance, or
     * `null` if the consumer reports a resolution without a payload (for
     * example, a confirmation accepted by default).
     */
    @Serializable
    @SerialName("EmissionEvent.Resolved")
    data class Resolved(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val emissionId: EmissionId,
        val affordanceId: AffordanceId,
        val replyContext: JsonElement? = null,
    ) : EmissionEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Emission resolved: $emissionId via $affordanceId")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "EmissionResolved"
        }
    }
}
