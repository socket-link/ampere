package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency

/**
 * Events related to product management activities.
 *
 * These events represent product-level activities such as feature requests,
 * epic definitions, and strategic planning items that need to be broken down
 * into actionable tasks.
 */
@Serializable
sealed interface ProductEvent : Event {

    /**
     * Emitted when a new feature is requested, typically from a specification
     * document, stakeholder request, or strategic planning artifact.
     *
     * The ProductManager agent can subscribe to these events to create
     * appropriate tickets and task breakdowns.
     */
    @Serializable
    data class FeatureRequested(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val featureTitle: String,
        val description: String,
        val requestedBy: AgentId,
        val phase: String? = null,
        val epic: String? = null,
        val act: String? = null,
        override val urgency: Urgency = Urgency.MEDIUM,
        val metadata: Map<String, String> = emptyMap(),
        val sourceFilePath: String? = null,
    ) : ProductEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Feature requested: $featureTitle")
            epic?.let { append(" [Epic: $it]") }
            phase?.let { append(" [Phase: $it]") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }


        companion object {
            const val EVENT_TYPE: EventType = "ProductFeatureRequested"
        }
    }

    /**
     * Emitted when an epic is defined or updated.
     *
     * Epics represent large bodies of work that contain multiple features.
     */
    @Serializable
    data class EpicDefined(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val epicTitle: String,
        val description: String,
        val phase: String? = null,
        val act: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val sourceFilePath: String? = null,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : ProductEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Epic defined: $epicTitle")
            phase?.let { append(" [Phase: $it]") }
            act?.let { append(" [Act: $it]") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }


        companion object {
            const val EVENT_TYPE: EventType = "ProductEpicDefined"
        }
    }

    /**
     * Emitted when a phase is defined or updated.
     *
     * Phases represent major organizational units containing multiple epics.
     */
    @Serializable
    data class PhaseDefined(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        val phaseTitle: String,
        val description: String,
        val act: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val sourceFilePath: String? = null,
    ) : ProductEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Phase defined: $phaseTitle")
            act?.let { append(" [Act: $it]") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }


        companion object {
            const val EVENT_TYPE: EventType = "ProductPhaseDefined"
        }
    }
}
