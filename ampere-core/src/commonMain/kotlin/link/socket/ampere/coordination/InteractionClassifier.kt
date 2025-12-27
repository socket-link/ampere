package link.socket.ampere.coordination

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.NotificationEvent

/**
 * Classifies events flowing through the EventSerialBus to identify inter-agent coordination.
 *
 * This classifier examines events and extracts coordination semantics, recognizing patterns
 * like task assignments, clarification requests, review requests, and escalations.
 */
class InteractionClassifier {

    companion object {
        private const val DEFAULT_SUMMARY_LENGTH = 100

        // Match @mentions that are preceded by whitespace or start of string
        // This prevents matching email addresses like alice@example.com
        private val MENTION_REGEX = Regex("""(?:^|\s)@(\w+)""")
    }

    /**
     * Classify an event as an agent interaction, or return null if the event
     * doesn't represent coordination between agents.
     *
     * @param event The event to classify
     * @return An AgentInteraction if this is a coordination event, null otherwise
     */
    fun classify(event: Event): AgentInteraction? {
        return when (event) {
            is Event.TaskCreated -> classifyTaskCreated(event)
            is Event.QuestionRaised -> classifyQuestionRaised(event)
            is Event.CodeSubmitted -> classifyCodeSubmitted(event)
            is NotificationEvent.ToAgent<*> -> classifyNotificationToAgent(event)
            is NotificationEvent.ToHuman<*> -> classifyNotificationToHuman(event)
            else -> null
        }
    }

    /**
     * Classify a TaskCreated event as a TICKET_ASSIGNED interaction.
     * Only creates an interaction if the task is assigned to a different agent.
     */
    private fun classifyTaskCreated(event: Event.TaskCreated): AgentInteraction? {
        val sourceAgentId = event.eventSource.getIdentifier()
        val targetAgentId = event.assignedTo

        return if (targetAgentId != null && sourceAgentId != targetAgentId) {
            AgentInteraction(
                sourceAgentId = sourceAgentId,
                targetAgentId = targetAgentId,
                interactionType = InteractionType.TICKET_ASSIGNED,
                timestamp = event.timestamp,
                eventId = event.eventId,
                context = truncate(event.description, DEFAULT_SUMMARY_LENGTH),
            )
        } else {
            null
        }
    }

    /**
     * Classify a QuestionRaised event as a CLARIFICATION_REQUEST.
     * Questions can be directed to specific agents via @mentions or broadcast to all.
     */
    private fun classifyQuestionRaised(event: Event.QuestionRaised): AgentInteraction? {
        val sourceAgentId = event.eventSource.getIdentifier()
        val targetAgentId = extractMention(event.questionText)

        return AgentInteraction(
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            interactionType = InteractionType.CLARIFICATION_REQUEST,
            timestamp = event.timestamp,
            eventId = event.eventId,
            context = truncate(event.questionText, DEFAULT_SUMMARY_LENGTH),
        )
    }

    /**
     * Classify a CodeSubmitted event as a REVIEW_REQUEST if review is required.
     * Only creates an interaction if review is required and assigned to an agent.
     */
    private fun classifyCodeSubmitted(event: Event.CodeSubmitted): AgentInteraction? {
        val sourceAgentId = event.eventSource.getIdentifier()
        val targetAgentId = event.assignedTo

        return if (event.reviewRequired && targetAgentId != null) {
            AgentInteraction(
                sourceAgentId = sourceAgentId,
                targetAgentId = targetAgentId,
                interactionType = InteractionType.REVIEW_REQUEST,
                timestamp = event.timestamp,
                eventId = event.eventId,
                context = "${event.filePath}: ${truncate(event.changeDescription, 80)}",
            )
        } else {
            null
        }
    }

    /**
     * Classify a NotificationEvent.ToAgent as various interaction types.
     * The specific type depends on the wrapped event.
     */
    private fun classifyNotificationToAgent(event: NotificationEvent.ToAgent<*>): AgentInteraction? {
        val sourceAgentId = event.event.eventSource.getIdentifier()
        val targetAgentId = event.agentId

        // Don't create interactions for self-notifications
        if (sourceAgentId == targetAgentId) {
            return null
        }

        val interactionType = determineInteractionTypeFromEvent(event.event)

        return AgentInteraction(
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            interactionType = interactionType,
            timestamp = event.timestamp,
            eventId = event.eventId,
            context = extractContextFromEvent(event.event),
        )
    }

    /**
     * Classify a NotificationEvent.ToHuman as a HUMAN_ESCALATION.
     */
    private fun classifyNotificationToHuman(event: NotificationEvent.ToHuman<*>): AgentInteraction? {
        val sourceAgentId = event.event.eventSource.getIdentifier()

        return AgentInteraction(
            sourceAgentId = sourceAgentId,
            targetAgentId = null, // Target is human, not an agent
            interactionType = InteractionType.HUMAN_ESCALATION,
            timestamp = event.timestamp,
            eventId = event.eventId,
            context = extractContextFromEvent(event.event),
        )
    }

    /**
     * Determine the interaction type based on the wrapped event in a notification.
     * This maps specific event types to their corresponding interaction types.
     */
    private fun determineInteractionTypeFromEvent(event: Event): InteractionType {
        return when (event) {
            is Event.TaskCreated -> InteractionType.DELEGATION
            is Event.QuestionRaised -> InteractionType.HELP_REQUEST
            is Event.CodeSubmitted -> InteractionType.REVIEW_REQUEST
            else -> InteractionType.DELEGATION
        }
    }

    /**
     * Extract context string from an event for the interaction context field.
     */
    private fun extractContextFromEvent(event: Event): String? {
        return when (event) {
            is Event.TaskCreated -> truncate(event.description, DEFAULT_SUMMARY_LENGTH)
            is Event.QuestionRaised -> truncate(event.questionText, DEFAULT_SUMMARY_LENGTH)
            is Event.CodeSubmitted -> "${event.filePath}: ${truncate(event.changeDescription, 80)}"
            else -> null
        }
    }

    /**
     * Extract an @mention from a text string.
     * Returns the first @mentioned agent ID, or null if no mention is found.
     *
     * @param content The text to search for @mentions
     * @return The mentioned agent ID, or null if no mention found
     */
    fun extractMention(content: String): AgentId? {
        val match = MENTION_REGEX.find(content)
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Truncate a string to a maximum length, adding an ellipsis if truncated.
     *
     * @param text The text to truncate
     * @param maxLength Maximum length of the result
     * @return The truncated text, with "..." appended if it was cut off
     */
    fun truncate(text: String, maxLength: Int): String {
        require(maxLength > 3) { "maxLength must be greater than 3 to accommodate ellipsis" }

        return if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength - 3) + "..."
        }
    }
}
