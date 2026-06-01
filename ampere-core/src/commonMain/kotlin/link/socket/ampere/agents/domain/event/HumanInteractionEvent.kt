package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.AffordanceId
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionId

/**
 * Named human-interaction specialisation of [EmissionEvent].
 *
 * `HumanInteractionEvent` extends [EmissionEvent] so every variant is
 * polymorphically an `EmissionEvent`. [InputRequested] additionally implements
 * [EmissionEvent.Produced] and [InputProvided] implements [EmissionEvent.Resolved],
 * so bus subscribers registered for the base event types automatically receive
 * specialised instances. Human-specific subscribers can narrow further by
 * checking `event is HumanInteractionEvent`.
 *
 * The attribution fields ([ticketId], [InputRequested.ticketId],
 * [InputRequested.taskId], [InputProvided.respondedBy]) are the load-bearing
 * fields that justify the specialisation over plain [EmissionEvent] — they
 * must survive the full request→response lifecycle.
 */
@Serializable
sealed interface HumanInteractionEvent : EmissionEvent {

    val requestId: String
    val agentId: AgentId

    /**
     * An agent is requesting human input.
     *
     * Implements [EmissionEvent.Produced] so subscribers on base
     * [EmissionEvent.Produced.EVENT_TYPE] ("EmissionProduced") also receive
     * these events polymorphically.
     *
     * @property requestId Unique identifier pairing this request with its response
     * @property agentId The agent requesting human input
     * @property ticketId Optional ticket this request is part of
     * @property taskId Optional task this request is part of
     */
    @Serializable
    @SerialName("HumanInteractionEvent.InputRequested")
    data class InputRequested(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        override val emission: Emission,
        override val requestId: String,
        override val agentId: AgentId,
        val ticketId: String? = null,
        val taskId: String? = null,
    ) : HumanInteractionEvent, EmissionEvent.Produced {

        override val eventType: EventType = EVENT_TYPE

        override val parentEventTypes: Set<EventType> = setOf(EmissionEvent.Produced.EVENT_TYPE)

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Human input requested by $agentId")
            val prompt = (emission.payload as? link.socket.ampere.agents.domain.emission.EmissionPayload.Decision)?.prompt
            prompt?.let { append(": $it") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "HumanInteractionRequested"
        }
    }

    /**
     * A human has provided a response to an agent's input request.
     *
     * Implements [EmissionEvent.Resolved] so subscribers on base
     * [EmissionEvent.Resolved.EVENT_TYPE] ("EmissionResolved") also receive
     * these events polymorphically.
     *
     * @property requestId The ID of the original [InputRequested] event
     * @property agentId The agent that requested input
     * @property respondedBy Optional identifier of the human who responded
     */
    @Serializable
    @SerialName("HumanInteractionEvent.InputProvided")
    data class InputProvided(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        override val emissionId: EmissionId,
        override val affordanceId: AffordanceId,
        override val replyContext: JsonElement? = null,
        override val requestId: String,
        override val agentId: AgentId,
        val respondedBy: String? = null,
    ) : HumanInteractionEvent, EmissionEvent.Resolved {

        override val eventType: EventType = EVENT_TYPE

        override val parentEventTypes: Set<EventType> = setOf(EmissionEvent.Resolved.EVENT_TYPE)

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Human response provided for request $requestId")
            respondedBy?.let { append(" by $it") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "HumanInteractionProvided"
        }
    }

    /**
     * A human input request timed out without receiving a response.
     *
     * Emitted by the DSL's timeout path when the suspended Emission was
     * produced as a [HumanInteractionEvent.InputRequested]. Plain
     * [EmissionEvent] timeouts throw [link.socket.ampere.agents.domain.emission.EmissionTimeout]
     * without publishing this specialised event.
     *
     * @property requestId The ID of the original [InputRequested] event
     * @property agentId The agent that requested input
     * @property timeoutMinutes How long the agent waited before timing out
     */
    @Serializable
    @SerialName("HumanInteractionEvent.RequestTimedOut")
    data class RequestTimedOut(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        override val emissionId: EmissionId,
        override val requestId: String,
        override val agentId: AgentId,
        val timeoutMinutes: Long,
    ) : HumanInteractionEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Human input request $requestId timed out")
            append(" after ${timeoutMinutes}m")
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "HumanInteractionTimedOut"
        }
    }
}
