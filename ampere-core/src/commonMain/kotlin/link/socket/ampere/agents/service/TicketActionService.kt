package link.socket.ampere.agents.service

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Service for executing ticket-related actions that affect the substrate.
 *
 * All operations emit events through the event bus to notify
 * agents and update the observable state.
 */
class TicketActionService(
    private val ticketRepository: TicketRepository,
    private val eventApi: AgentEventApi,
) {
    /**
     * Create a new ticket and emit TicketCreated event.
     */
    suspend fun createTicket(
        title: String,
        description: String,
        priority: TicketPriority,
        type: TicketType = TicketType.TASK,
    ): Result<Ticket> {
        return try {
            val now = Clock.System.now()
            val ticketId = generateTicketId()

            val ticket = Ticket(
                id = ticketId,
                title = title,
                description = description,
                type = type,
                priority = priority,
                status = TicketStatus.Backlog,
                assignedAgentId = null,
                createdByAgentId = "human-cli",
                createdAt = now,
                updatedAt = now,
                dueDate = null,
            )

            ticketRepository.createTicket(ticket)
                .onSuccess {
                    // Emit event to notify the substrate
                    val event = TicketEvent.TicketCreated(
                        eventId = generateUUID(ticketId),
                        ticketId = ticket.id,
                        title = ticket.title,
                        description = ticket.description,
                        ticketType = ticket.type,
                        priority = ticket.priority,
                        eventSource = EventSource.Human,
                        timestamp = now,
                        urgency = when (priority) {
                            TicketPriority.CRITICAL -> Urgency.HIGH
                            TicketPriority.HIGH -> Urgency.HIGH
                            TicketPriority.MEDIUM -> Urgency.MEDIUM
                            TicketPriority.LOW -> Urgency.LOW
                        },
                    )
                    eventApi.publish(event)
                }

            Result.success(ticket)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Assign a ticket to an agent and emit TicketAssigned event.
     */
    suspend fun assignTicket(
        ticketId: TicketId,
        agentId: AgentId?,
    ): Result<Unit> {
        return try {
            ticketRepository.assignTicket(ticketId, agentId)
                .onSuccess {
                    // Emit event to notify the substrate
                    val event = TicketEvent.TicketAssigned(
                        eventId = generateUUID(ticketId),
                        ticketId = ticketId,
                        assignedTo = agentId,
                        eventSource = EventSource.Human,
                        timestamp = Clock.System.now(),
                        urgency = Urgency.MEDIUM,
                    )
                    eventApi.publish(event)
                }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update ticket status and emit TicketStatusChanged event.
     */
    suspend fun updateStatus(
        ticketId: TicketId,
        newStatus: TicketStatus,
    ): Result<Unit> {
        return try {
            // Get the current ticket to find old status
            val ticket = ticketRepository.getTicket(ticketId)
                .getOrNull()
                ?: return Result.failure(IllegalArgumentException("Ticket not found: $ticketId"))

            val oldStatus = ticket.status

            ticketRepository.updateStatus(ticketId, newStatus)
                .onSuccess {
                    // Emit event to notify the substrate
                    val event = TicketEvent.TicketStatusChanged(
                        eventId = generateUUID(ticketId),
                        ticketId = ticketId,
                        previousStatus = oldStatus,
                        newStatus = newStatus,
                        eventSource = EventSource.Human,
                        timestamp = Clock.System.now(),
                        urgency = Urgency.MEDIUM,
                    )
                    eventApi.publish(event)
                }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateTicketId(): TicketId {
        return "ticket-${Clock.System.now().toEpochMilliseconds()}"
    }
}
