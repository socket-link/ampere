package link.socket.ampere.agents.events.tickets

import link.socket.ampere.agents.domain.concept.status.TicketStatus

/**
 * Default implementation of TicketViewService that queries ticket state
 * through TicketRepository and transforms it into presentation-friendly formats.
 */
class DefaultTicketViewService(
    private val ticketRepository: TicketRepository,
) : TicketViewService {

    override suspend fun listActiveTickets(): Result<List<TicketSummary>> =
        ticketRepository.getAllTickets().mapCatching { tickets ->
            tickets
                .filter { it.status != TicketStatus.Done }
                .sortedWith(
                    compareByDescending<Ticket> { it.priority.ordinal }
                        .thenBy { it.createdAt },
                )
                .map { ticket ->
                    TicketSummary(
                        ticketId = ticket.id,
                        title = ticket.title,
                        status = ticket.status.name,
                        assigneeId = ticket.assignedAgentId,
                        priority = ticket.priority.name,
                        createdAt = ticket.createdAt,
                    )
                }
        }

    override suspend fun getTicketDetail(ticketId: TicketId): Result<TicketDetail> =
        ticketRepository.getTicket(ticketId).mapCatching { ticket ->
            val nonNullTicket = ticket ?: throw TicketError.TicketNotFound(ticketId)

            TicketDetail(
                ticketId = nonNullTicket.id,
                title = nonNullTicket.title,
                description = nonNullTicket.description,
                acceptanceCriteria = extractAcceptanceCriteria(nonNullTicket.description),
                status = nonNullTicket.status.name,
                assigneeId = nonNullTicket.assignedAgentId,
                priority = nonNullTicket.priority.name,
                type = nonNullTicket.type.name,
                createdAt = nonNullTicket.createdAt,
                updatedAt = nonNullTicket.updatedAt,
                relatedThreadId = null, // TODO: Add thread relationship when available
                dueDate = nonNullTicket.dueDate,
                createdByAgentId = nonNullTicket.createdByAgentId,
            )
        }

    /**
     * Extracts acceptance criteria from the ticket description.
     * Looks for patterns like:
     * - Bullet points (lines starting with "- " or "* ")
     * - Numbered lists (lines starting with digits followed by "." or ")")
     * - Checkbox lists (lines starting with "- [ ]" or "- [x]")
     *
     * If a section explicitly titled "Acceptance Criteria:" is found,
     * only items under that section are extracted.
     */
    private fun extractAcceptanceCriteria(description: String): List<String> {
        val lines = description.lines()

        // Check if there's an explicit "Acceptance Criteria" section
        val acIndex = lines.indexOfFirst { line ->
            line.trim().startsWith("Acceptance Criteria", ignoreCase = true) ||
                line.trim().startsWith("AC:", ignoreCase = true) ||
                line.trim().startsWith("Acceptance:", ignoreCase = true)
        }

        val relevantLines = if (acIndex >= 0) {
            // Find the next section header or end of document
            val nextSectionIndex = lines.subList(acIndex + 1, lines.size)
                .indexOfFirst { line ->
                    val trimmed = line.trim()
                    trimmed.endsWith(":") &&
                        trimmed.isNotEmpty() &&
                        trimmed.first().isUpperCase() &&
                        !trimmed.matches(Regex("^[-*].*"))
                }

            val endIndex = if (nextSectionIndex >= 0) {
                acIndex + 1 + nextSectionIndex
            } else {
                lines.size
            }

            lines.subList(acIndex + 1, endIndex)
        } else {
            // No explicit section, use all lines
            lines
        }

        // Extract items from relevant lines
        return relevantLines
            .mapNotNull { line ->
                val trimmed = line.trim()
                when {
                    // Checkbox items: "- [ ] item" or "- [x] item"
                    trimmed.matches(Regex("^[-*]\\s*\\[[ xX]]\\s+(.+)")) -> {
                        Regex("^[-*]\\s*\\[[ xX]]\\s+(.+)").find(trimmed)?.groupValues?.get(1)
                    }
                    // Bullet points: "- item" or "* item"
                    trimmed.matches(Regex("^[-*]\\s+(.+)")) -> {
                        Regex("^[-*]\\s+(.+)").find(trimmed)?.groupValues?.get(1)
                    }
                    // Numbered items: "1. item" or "1) item"
                    trimmed.matches(Regex("^\\d+[.)]\\s+(.+)")) -> {
                        Regex("^\\d+[.)]\\s+(.+)").find(trimmed)?.groupValues?.get(1)
                    }
                    else -> null
                }
            }
            .filter { it.isNotBlank() }
    }
}
