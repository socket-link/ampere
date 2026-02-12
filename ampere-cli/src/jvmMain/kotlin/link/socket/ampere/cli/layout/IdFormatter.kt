package link.socket.ampere.cli.layout

/**
 * Utilities for formatting IDs in a human-friendly way.
 *
 * Full UUIDs like `e80c6ea3-c092-4be2-bb1c-228d32e90f1d` consume valuable
 * horizontal space without adding actionable information. This utility
 * truncates UUIDs to a shorter format while preserving uniqueness.
 */
object IdFormatter {

    private val UUID_REGEX = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE
    )

    /**
     * Truncates a UUID to last N characters with ellipsis.
     *
     * Example: `e80c6ea3-c092-4be2-bb1c-228d32e90f1d` → `...32e90f1d`
     *
     * @param uuid The UUID string to truncate
     * @param lastChars Number of trailing characters to keep (default 8)
     * @return Truncated ID with ellipsis prefix
     */
    fun truncateUuid(uuid: String, lastChars: Int = 8): String {
        val clean = uuid.replace("-", "")
        return if (clean.length > lastChars) {
            "...${clean.takeLast(lastChars)}"
        } else {
            clean
        }
    }

    /**
     * Formats a ticket ID for display.
     *
     * @param ticketId The full ticket ID
     * @return Truncated ticket ID
     */
    fun formatTicketId(ticketId: String): String {
        return truncateUuid(ticketId)
    }

    /**
     * Formats an agent ID, preferring the agent name if available.
     *
     * If the agent ID looks like a UUID, truncates it. Otherwise keeps it as-is.
     *
     * @param agentId The full agent ID
     * @param agentName Optional human-readable agent name
     * @return Formatted agent identifier
     */
    fun formatAgentId(agentId: String, agentName: String? = null): String {
        // Prefer human-readable name
        if (!agentName.isNullOrBlank()) {
            return agentName
        }

        // Check if it looks like a UUID
        return if (UUID_REGEX.matches(agentId)) {
            truncateUuid(agentId)
        } else {
            // Keep short agent names as-is
            agentId.takeLast(20)
        }
    }

    /**
     * Replaces all UUIDs in a string with truncated versions.
     *
     * Useful for formatting event descriptions or messages that contain
     * embedded UUIDs.
     *
     * @param text The text containing UUIDs
     * @param lastChars Number of trailing characters to keep
     * @return Text with all UUIDs truncated
     */
    fun truncateUuidsInText(text: String, lastChars: Int = 8): String {
        return UUID_REGEX.replace(text) { match ->
            truncateUuid(match.value, lastChars)
        }
    }

    /**
     * Formats a thread ID for display.
     *
     * @param threadId The full thread ID
     * @return Truncated thread ID
     */
    fun formatThreadId(threadId: String): String {
        return truncateUuid(threadId)
    }
}
