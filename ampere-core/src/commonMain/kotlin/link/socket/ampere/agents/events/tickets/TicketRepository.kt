package link.socket.ampere.agents.events.tickets

import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.withContext
import link.socket.ampere.util.ioDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.meetings.MeetingId
import link.socket.ampere.db.Database
import link.socket.ampere.db.tickets.TicketStore

/**
 * Sealed class representing errors that can occur during ticket operations.
 */
sealed class TicketError : Exception() {

    /**
     * Error when attempting an invalid state transition.
     */
    data class InvalidStateTransition(
        val fromState: TicketStatus,
        val toState: TicketStatus,
    ) : TicketError() {
        override val message: String
            get() = "Invalid state transition: cannot transition from $fromState to $toState. " +
                "Valid transitions from $fromState are: ${fromState.validTransitions()}"
    }

    /**
     * Error when a ticket is not found.
     */
    data class TicketNotFound(
        val ticketId: TicketId,
    ) : TicketError() {
        override val message: String
            get() = "Ticket not found: $ticketId"
    }

    /**
     * Error wrapping database exceptions.
     */
    data class DatabaseError(
        override val cause: Throwable,
    ) : TicketError() {
        override val message: String
            get() = "Database error: ${cause.message}"
    }

    /**
     * Error for validation failures.
     */
    data class ValidationError(
        override val message: String,
    ) : TicketError()
}

/**
 * Repository for persisting and querying Tickets using SQLDelight.
 *
 * This handles conversion between domain models and database representations,
 * with proper error handling and state transition validation.
 */
class TicketRepository(
    private val database: Database,
) {

    private val ticketQueries get() = database.ticketQueries
    private val ticketMeetingQueries get() = database.ticketMeetingQueries

    /**
     * Create a new ticket in the database.
     *
     * @param ticket The ticket to create.
     * @return Result containing the created ticket or a TicketError.
     */
    suspend fun createTicket(ticket: Ticket): Result<Ticket> =
        withContext(ioDispatcher) {
            try {
                ticketQueries.insertTicket(
                    id = ticket.id,
                    title = ticket.title,
                    description = ticket.description,
                    ticket_type = ticket.type.name,
                    priority = ticket.priority.name,
                    status = ticket.status.name,
                    assigned_agent_id = ticket.assignedAgentId,
                    created_by_agent_id = ticket.createdByAgentId,
                    created_at = ticket.createdAt.toEpochMilliseconds(),
                    updated_at = ticket.updatedAt.toEpochMilliseconds(),
                    due_date = ticket.dueDate?.toEpochMilliseconds(),
                )
                Result.success(ticket)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Update the status of a ticket with state transition validation.
     *
     * @param ticketId The ID of the ticket to update.
     * @param newStatus The target status.
     * @return Result containing Unit on success or a TicketError.
     */
    suspend fun updateStatus(ticketId: TicketId, newStatus: TicketStatus): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                // First, get the current ticket to validate transition
                val currentTicket = getTicketInternal(ticketId)
                    ?: return@withContext Result.failure(TicketError.TicketNotFound(ticketId))

                // Validate the state transition
                if (!currentTicket.status.canTransitionTo(newStatus)) {
                    return@withContext Result.failure(
                        TicketError.InvalidStateTransition(
                            fromState = currentTicket.status,
                            toState = newStatus,
                        ),
                    )
                }

                // Perform the update
                val now = Clock.System.now().toEpochMilliseconds()
                ticketQueries.updateTicketStatus(
                    status = newStatus.name,
                    updated_at = now,
                    id = ticketId,
                )

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Assign a ticket to an agent.
     *
     * @param ticketId The ID of the ticket to assign.
     * @param agentId The ID of the agent to assign to, or null to unassign.
     * @return Result containing Unit on success or a TicketError.
     */
    suspend fun assignTicket(ticketId: TicketId, agentId: AgentId?): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                // Verify ticket exists
                val ticket = getTicketInternal(ticketId)
                    ?: return@withContext Result.failure(TicketError.TicketNotFound(ticketId))

                val now = Clock.System.now().toEpochMilliseconds()
                ticketQueries.updateTicketAssignment(
                    assigned_agent_id = agentId,
                    updated_at = now,
                    id = ticketId,
                )

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Retrieve a ticket by its ID.
     *
     * @param ticketId The ID of the ticket to retrieve.
     * @return Result containing the ticket (or null if not found) or a TicketError.
     */
    suspend fun getTicket(ticketId: TicketId): Result<Ticket?> =
        withContext(ioDispatcher) {
            try {
                val ticket = getTicketInternal(ticketId)
                Result.success(ticket)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets with a specific status.
     *
     * @param status The status to filter by.
     * @return Result containing the list of tickets or a TicketError.
     */
    suspend fun getTicketsByStatus(status: TicketStatus): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketQueries.getTicketsByStatus(status.name)
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets assigned to a specific agent.
     *
     * @param agentId The ID of the agent.
     * @return Result containing the list of tickets or a TicketError.
     */
    suspend fun getTicketsByAgent(agentId: AgentId): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketQueries.getTicketsByAssignedAgent(agentId)
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets.
     *
     * @return Result containing the list of all tickets or a TicketError.
     */
    suspend fun getAllTickets(): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketQueries.getAllTickets()
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets by priority.
     *
     * @param priority The priority to filter by.
     * @return Result containing the list of tickets or a TicketError.
     */
    suspend fun getTicketsByPriority(priority: TicketPriority): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketQueries.getTicketsByPriority(priority.name)
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets by type.
     *
     * @param type The type to filter by.
     * @return Result containing the list of tickets or a TicketError.
     */
    suspend fun getTicketsByType(type: TicketType): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketQueries.getTicketsByType(type.name)
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets created by a specific agent.
     *
     * @param agentId The ID of the creator agent.
     * @return Result containing the list of tickets or a TicketError.
     */
    suspend fun getTicketsByCreator(agentId: AgentId): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketQueries.getTicketsByCreator(agentId)
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Update ticket details.
     *
     * @param ticketId The ID of the ticket to update.
     * @param title New title (optional).
     * @param description New description (optional).
     * @param priority New priority (optional).
     * @param dueDate New due date (optional).
     * @return Result containing the updated ticket or a TicketError.
     */
    suspend fun updateTicketDetails(
        ticketId: TicketId,
        title: String? = null,
        description: String? = null,
        priority: TicketPriority? = null,
        dueDate: Instant? = null,
    ): Result<Ticket> =
        withContext(ioDispatcher) {
            try {
                // Get current ticket
                val currentTicket = getTicketInternal(ticketId)
                    ?: return@withContext Result.failure(TicketError.TicketNotFound(ticketId))

                val now = Clock.System.now().toEpochMilliseconds()
                ticketQueries.updateTicketDetails(
                    title = title ?: currentTicket.title,
                    description = description ?: currentTicket.description,
                    priority = (priority ?: currentTicket.priority).name,
                    due_date = (dueDate ?: currentTicket.dueDate)?.toEpochMilliseconds(),
                    updated_at = now,
                    id = ticketId,
                )

                // Return updated ticket
                val updatedTicket = getTicketInternal(ticketId)
                    ?: return@withContext Result.failure(TicketError.TicketNotFound(ticketId))
                Result.success(updatedTicket)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Delete a ticket.
     *
     * @param ticketId The ID of the ticket to delete.
     * @return Result containing Unit on success or a TicketError.
     */
    suspend fun deleteTicket(ticketId: TicketId): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                ticketQueries.deleteTicket(ticketId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    // ==================== Backlog Analytics Methods ====================

    /**
     * Get a summary of the current backlog state.
     *
     * @return Result containing the BacklogSummary or a TicketError.
     */
    suspend fun getBacklogSummary(): Result<BacklogSummary> =
        withContext(ioDispatcher) {
            try {
                val allTickets = ticketQueries.getAllTickets()
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }

                val now = Clock.System.now()

                val ticketsByStatus = allTickets
                    .groupBy { it.status }
                    .mapValues { it.value.size }

                val ticketsByPriority = allTickets
                    .groupBy { it.priority }
                    .mapValues { it.value.size }

                val ticketsByType = allTickets
                    .groupBy { it.type }
                    .mapValues { it.value.size }

                val blockedCount = allTickets.count { it.status == TicketStatus.Blocked }

                val overdueCount = allTickets.count { ticket ->
                    ticket.dueDate != null &&
                        ticket.dueDate < now &&
                        ticket.status != TicketStatus.Done
                }

                Result.success(
                    BacklogSummary(
                        totalTickets = allTickets.size,
                        ticketsByStatus = ticketsByStatus,
                        ticketsByPriority = ticketsByPriority,
                        ticketsByType = ticketsByType,
                        blockedCount = blockedCount,
                        overdueCount = overdueCount,
                    ),
                )
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get the workload summary for a specific agent.
     *
     * @param agentId The ID of the agent.
     * @return Result containing the AgentWorkload or a TicketError.
     */
    suspend fun getAgentWorkload(agentId: AgentId): Result<AgentWorkload> =
        withContext(ioDispatcher) {
            try {
                val assignedTickets = ticketQueries.getTicketsByAssignedAgent(agentId)
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }

                val inProgressCount = assignedTickets.count { it.status == TicketStatus.InProgress }
                val blockedCount = assignedTickets.count { it.status == TicketStatus.Blocked }
                val completedCount = assignedTickets.count { it.status == TicketStatus.Done }

                Result.success(
                    AgentWorkload(
                        agentId = agentId,
                        assignedTickets = assignedTickets,
                        inProgressCount = inProgressCount,
                        blockedCount = blockedCount,
                        completedCount = completedCount,
                    ),
                )
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get tickets with due dates within the specified number of days.
     *
     * @param daysAhead Number of days to look ahead for deadlines.
     * @return Result containing the list of tickets sorted by due date ascending, or a TicketError.
     */
    suspend fun getUpcomingDeadlines(daysAhead: Int): Result<List<Ticket>> =
        withContext(ioDispatcher) {
            try {
                val now = Clock.System.now()
                val futureLimit = now + daysAhead.days

                val allTickets = ticketQueries.getAllTickets()
                    .executeAsList()
                    .map { row -> mapRowToTicket(row) }

                val upcomingTickets = allTickets
                    .filter { ticket ->
                        ticket.dueDate != null &&
                            ticket.dueDate >= now &&
                            ticket.dueDate <= futureLimit &&
                            ticket.status != TicketStatus.Done
                    }
                    .sortedBy { it.dueDate }

                Result.success(upcomingTickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    // ==================== Ticket-Meeting Association Methods ====================

    /**
     * Associate a ticket with a meeting.
     *
     * @param ticketId The ID of the ticket.
     * @param meetingId The ID of the meeting.
     * @return Result containing the created TicketMeeting or a TicketError.
     */
    suspend fun addTicketMeeting(ticketId: TicketId, meetingId: MeetingId): Result<TicketMeeting> =
        withContext(ioDispatcher) {
            try {
                val now = Clock.System.now()
                ticketMeetingQueries.insertTicketMeeting(
                    ticket_id = ticketId,
                    meeting_id = meetingId,
                    created_at = now.toEpochMilliseconds(),
                )
                Result.success(TicketMeeting(ticketId, meetingId, now))
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all meetings associated with a ticket.
     *
     * @param ticketId The ID of the ticket.
     * @return Result containing the list of TicketMeetings or a TicketError.
     */
    suspend fun getMeetingsForTicket(ticketId: TicketId): Result<List<TicketMeeting>> =
        withContext(ioDispatcher) {
            try {
                val meetings = ticketMeetingQueries.getMeetingsByTicket(ticketId)
                    .executeAsList()
                    .map { row ->
                        TicketMeeting(
                            ticketId = ticketId,
                            meetingId = row.meeting_id,
                            createdAt = Instant.fromEpochMilliseconds(row.created_at),
                        )
                    }
                Result.success(meetings)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Get all tickets associated with a meeting.
     *
     * @param meetingId The ID of the meeting.
     * @return Result containing the list of TicketMeetings or a TicketError.
     */
    suspend fun getTicketsForMeeting(meetingId: MeetingId): Result<List<TicketMeeting>> =
        withContext(ioDispatcher) {
            try {
                val tickets = ticketMeetingQueries.getTicketsByMeeting(meetingId)
                    .executeAsList()
                    .map { row ->
                        TicketMeeting(
                            ticketId = row.ticket_id,
                            meetingId = meetingId,
                            createdAt = Instant.fromEpochMilliseconds(row.created_at),
                        )
                    }
                Result.success(tickets)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    /**
     * Remove a ticket-meeting association.
     *
     * @param ticketId The ID of the ticket.
     * @param meetingId The ID of the meeting.
     * @return Result containing Unit on success or a TicketError.
     */
    suspend fun removeTicketMeeting(ticketId: TicketId, meetingId: MeetingId): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                ticketMeetingQueries.deleteTicketMeeting(ticketId, meetingId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(TicketError.DatabaseError(e))
            }
        }

    // ==================== Private Helpers ====================

    /**
     * Internal helper to get a ticket without wrapping in Result.
     */
    private fun getTicketInternal(ticketId: TicketId): Ticket? {
        val row = ticketQueries.getTicketById(ticketId).executeAsOneOrNull()
            ?: return null
        return mapRowToTicket(row)
    }

    /**
     * Map a database row to a Ticket domain object.
     */
    private fun mapRowToTicket(row: TicketStore): Ticket {
        return Ticket(
            id = row.id,
            title = row.title,
            description = row.description,
            type = TicketType.valueOf(row.ticket_type),
            priority = TicketPriority.valueOf(row.priority),
            status = TicketStatus.fromString(row.status),
            assignedAgentId = row.assigned_agent_id,
            createdByAgentId = row.created_by_agent_id,
            createdAt = Instant.fromEpochMilliseconds(row.created_at),
            updatedAt = Instant.fromEpochMilliseconds(row.updated_at),
            dueDate = row.due_date?.let { Instant.fromEpochMilliseconds(it) },
        )
    }
}
