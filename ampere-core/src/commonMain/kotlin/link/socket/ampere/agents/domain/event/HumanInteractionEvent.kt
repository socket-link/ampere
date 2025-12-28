package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency

/**
 * Events related to human interaction requests from agents.
 *
 * These events are emitted when agents need human guidance, approval, or
 * input to continue execution. They represent critical decision points where
 * agent autonomy is insufficient.
 */
@Serializable
sealed interface HumanInteractionEvent : Event {

    /**
     * An agent is requesting human input to continue execution.
     *
     * Emitted when:
     * - Agent encounters ambiguity or uncertainty beyond its confidence threshold
     * - Tool execution requires human approval (e.g., AskHumanTool)
     * - Critical decision needs human oversight
     *
     * The requesting agent will block execution until a human provides a response.
     *
     * @property requestId Unique identifier for this human interaction request
     * @property agentId The agent requesting human input
     * @property question The question or prompt for the human
     * @property context Additional context to help the human understand the situation
     * @property ticketId Optional ticket ID if this request is part of ticket work
     * @property taskId Optional task ID if this request is part of a specific task
     */
    @Serializable
    data class InputRequested(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val requestId: String,
        val agentId: AgentId,
        val question: String,
        val context: Map<String, String> = emptyMap(),
        val ticketId: String? = null,
        val taskId: String? = null,
    ) : HumanInteractionEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Human input requested by $agentId")
            append(" - $question")
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "HumanInputRequested"
        }
    }

    /**
     * A human has provided a response to an agent's input request.
     *
     * Emitted when:
     * - Human provides input through CLI, UI, or API
     * - Response is delivered to the waiting agent
     *
     * @property requestId The ID of the original InputRequested event
     * @property agentId The agent that requested input
     * @property response The human's response text
     * @property respondedBy Optional identifier of the human who responded
     */
    @Serializable
    data class InputProvided(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val requestId: String,
        val agentId: AgentId,
        val response: String,
        val respondedBy: String? = null,
    ) : HumanInteractionEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Human response provided for request $requestId")
            respondedBy?.let { append(" by $it") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "HumanInputProvided"
        }
    }

    /**
     * A human input request timed out without receiving a response.
     *
     * Emitted when:
     * - Request timeout expires before human responds
     * - Agent may continue with fallback behavior or fail the operation
     *
     * @property requestId The ID of the original InputRequested event
     * @property agentId The agent that requested input
     * @property timeoutMinutes How long the agent waited
     */
    @Serializable
    data class RequestTimedOut(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val requestId: String,
        val agentId: AgentId,
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
            const val EVENT_TYPE: EventType = "HumanInputRequestTimedOut"
        }
    }
}
