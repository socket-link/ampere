package link.socket.ampere.cli.goal

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.tickets.TicketBuilder
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketSpecification
import link.socket.ampere.agents.events.tickets.TicketType

/**
 * Parses goal strings into TicketSpecifications.
 *
 * This utility provides shared parsing logic for goals entered via:
 * - CLI argument: `ampere --goal "Write FizzBuzz"`
 * - TUI command: `:goal Write FizzBuzz`
 *
 * Features:
 * - Smart title extraction (first sentence or truncated description)
 * - Type inference from keywords (fix/bug → BUG, implement/add → FEATURE)
 * - Priority inference from keywords (urgent/critical → CRITICAL)
 */
object GoalParser {

    private const val MAX_TITLE_LENGTH = 60
    private const val TRUNCATED_TITLE_LENGTH = 57
    private const val DEFAULT_CREATOR_ID = "human-cli"

    /**
     * Parse a goal description into a TicketSpecification.
     *
     * @param goalDescription The user-provided goal text
     * @param createdBy Agent ID of the creator (default: "human-cli")
     * @param assignedTo Optional agent ID to pre-assign the ticket
     * @return A validated TicketSpecification ready for submission
     */
    fun parse(
        goalDescription: String,
        createdBy: AgentId = DEFAULT_CREATOR_ID,
        assignedTo: AgentId? = null,
    ): TicketSpecification {
        val trimmedDescription = goalDescription.trim()

        return TicketBuilder()
            .withTitle(extractTitle(trimmedDescription))
            .withDescription(trimmedDescription)
            .ofType(inferType(trimmedDescription))
            .withPriority(inferPriority(trimmedDescription))
            .createdBy(createdBy)
            .apply { assignedTo?.let { assignedTo(it) } }
            .build()
    }

    /**
     * Extract a concise title from the goal description.
     *
     * Strategy:
     * 1. If the description has a clear first sentence (ends with . ! ?),
     *    use that if it's short enough
     * 2. Otherwise, truncate to MAX_TITLE_LENGTH with ellipsis
     */
    internal fun extractTitle(description: String): String {
        // Try to find a natural sentence break
        val sentenceEndPattern = Regex("[.!?]")
        val firstSentenceEnd = sentenceEndPattern.find(description)?.range?.first

        if (firstSentenceEnd != null) {
            val firstSentence = description.substring(0, firstSentenceEnd).trim()
            if (firstSentence.length <= MAX_TITLE_LENGTH && firstSentence.isNotEmpty()) {
                return firstSentence
            }
        }

        // No suitable sentence break, truncate with ellipsis
        return if (description.length > MAX_TITLE_LENGTH) {
            description.take(TRUNCATED_TITLE_LENGTH).trim() + "..."
        } else {
            description
        }
    }

    /**
     * Infer the ticket type from keywords in the description.
     *
     * Keywords checked:
     * - BUG: "fix", "bug", "broken", "error", "issue"
     * - FEATURE: "implement", "add", "create", "build", "new"
     * - SPIKE: "research", "investigate", "explore", "prototype", "spike"
     * - TASK: default fallback
     */
    internal fun inferType(description: String): TicketType {
        val lower = description.lowercase()

        return when {
            // Bug indicators
            lower.containsAny("fix", "bug", "broken", "error", "issue", "crash") ->
                TicketType.BUG

            // Feature indicators
            lower.containsAny("implement", "add", "create", "build", "new", "feature") ->
                TicketType.FEATURE

            // Spike/research indicators
            lower.containsAny("research", "investigate", "explore", "prototype", "spike", "experiment") ->
                TicketType.SPIKE

            // Default to TASK
            else -> TicketType.TASK
        }
    }

    /**
     * Infer the ticket priority from keywords in the description.
     *
     * Keywords checked:
     * - CRITICAL: "urgent", "critical", "emergency", "asap", "immediately"
     * - HIGH: "important", "high priority", "soon"
     * - LOW: "low", "minor", "when possible", "nice to have"
     * - MEDIUM: default fallback
     */
    internal fun inferPriority(description: String): TicketPriority {
        val lower = description.lowercase()

        return when {
            // Critical indicators
            lower.containsAny("urgent", "critical", "emergency", "asap", "immediately") ->
                TicketPriority.CRITICAL

            // High priority indicators
            lower.containsAny("important", "high priority", "high-priority", "soon", "priority") ->
                TicketPriority.HIGH

            // Low priority indicators
            lower.containsAny("low priority", "low-priority", "minor", "when possible", "nice to have", "eventually") ->
                TicketPriority.LOW

            // Default to MEDIUM
            else -> TicketPriority.MEDIUM
        }
    }

    /**
     * Extension function to check if a string contains any of the given keywords.
     */
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { keyword ->
            // Use word boundary matching to avoid false positives
            // e.g., "addition" shouldn't match "add"
            this.contains(Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE))
        }
    }
}
