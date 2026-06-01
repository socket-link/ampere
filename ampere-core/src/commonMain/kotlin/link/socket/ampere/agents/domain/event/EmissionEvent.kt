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
 * `EmissionEvent` is a sealed interface with two sub-interfaces — [Produced]
 * and [Resolved] — that define the lifecycle contract. Concrete base
 * implementations ([BaseProduced] / [BaseResolved]) serve generic Emissions;
 * human-interaction specialisations ([HumanInteractionEvent.InputRequested] /
 * [HumanInteractionEvent.InputProvided]) implement the sub-interfaces directly,
 * making them polymorphically substitutable everywhere a [Produced] or
 * [Resolved] is expected.
 *
 * Bus subscribers registered for [Produced.EVENT_TYPE] receive both
 * [BaseProduced] and [HumanInteractionEvent.InputRequested] events. Specialised
 * subscribers can narrow by checking `event is HumanInteractionEvent`.
 */
@Serializable
sealed interface EmissionEvent : Event {

    /** The Emission whose lifecycle this event refers to. */
    val emissionId: EmissionId

    /** Contract satisfied by any event that publishes an [Emission]. */
    interface Produced : EmissionEvent {
        val emission: Emission
        override val emissionId: EmissionId get() = emission.id

        companion object {
            const val EVENT_TYPE: EventType = "EmissionProduced"
        }
    }

    /**
     * Contract satisfied by any event that resolves an [Emission] via an
     * affordance reply. `replyContext` is the (opaque to AMPERE) payload of
     * the chosen affordance, or `null` when the resolution carries no payload.
     */
    interface Resolved : EmissionEvent {
        val affordanceId: AffordanceId
        val replyContext: JsonElement?

        companion object {
            const val EVENT_TYPE: EventType = "EmissionResolved"
        }
    }

    /** Concrete base implementation of [Produced] for generic Emissions. */
    @Serializable
    @SerialName("EmissionEvent.Produced")
    data class BaseProduced(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
        override val emission: Emission,
    ) : EmissionEvent, Produced {

        override val eventType: EventType = Produced.EVENT_TYPE

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
    }

    /** Concrete base implementation of [Resolved] for generic Emissions. */
    @Serializable
    @SerialName("EmissionEvent.Resolved")
    data class BaseResolved(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val emissionId: EmissionId,
        override val affordanceId: AffordanceId,
        override val replyContext: JsonElement? = null,
    ) : EmissionEvent, Resolved {

        override val eventType: EventType = Resolved.EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Emission resolved: $emissionId via $affordanceId")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }
    }
}
