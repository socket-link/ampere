package link.socket.ampere.agents.execution

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType

class ExecutionModelsTest {

    private val now: Instant = Clock.System.now()

    private fun createTestTicket(
        id: String = "ticket-1",
        title: String = "Test Ticket",
        description: String = "Test description",
    ): Ticket = Ticket(
        id = id,
        title = title,
        description = description,
        type = TicketType.TASK,
        priority = TicketPriority.MEDIUM,
        status = TicketStatus.InProgress,
        assignedAgentId = "agent-1",
        createdByAgentId = "pm-agent",
        createdAt = now,
        updatedAt = now,
    )

    // TODO: Add tests for ExecutionContext subtypes
}
