package link.socket.kore.agents.events.tickets

import kotlinx.datetime.Clock
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.events.TicketEvent
import link.socket.kore.agents.events.Urgency
import link.socket.kore.agents.events.bus.EventBus
import link.socket.kore.agents.events.messages.AgentMessageApi
import link.socket.kore.agents.events.messages.MessageChannel
import link.socket.kore.agents.events.messages.MessageThread
import link.socket.kore.agents.events.utils.ConsoleEventLogger
import link.socket.kore.agents.events.utils.EventLogger
import link.socket.kore.util.randomUUID

/**
 * Service layer that coordinates ticket lifecycle operations, integrates with EventBus
 * for event publishing, and handles MessageThread creation for ticket discussions.
 */
class TicketOrchestrator(
    private val ticketRepository: TicketRepository,
    private val eventBus: EventBus,
    private val messageApi: AgentMessageApi,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /**
     * Create a new ticket, publish a TicketCreated event, and create a MessageThread
     * for ticket discussion.
     *
     * @param title Brief summary of the work item.
     * @param description Detailed description of requirements.
     * @param type Category of work item.
     * @param priority Priority level for scheduling.
     * @param createdByAgentId Agent that is creating this ticket.
     * @return Result containing the created ticket and its associated message thread.
     */
    suspend fun createTicket(
        title: String,
        description: String,
        type: TicketType,
        priority: TicketPriority,
        createdByAgentId: AgentId,
    ): Result<Pair<Ticket, MessageThread>> {
        // Validate inputs
        if (title.isBlank()) {
            return Result.failure(
                TicketError.ValidationError("Ticket title cannot be blank")
            )
        }

        val now = Clock.System.now()

        // Create ticket entity
        val ticket = Ticket(
            id = randomUUID(),
            title = title,
            description = description,
            type = type,
            priority = priority,
            status = TicketStatus.BACKLOG,
            assignedAgentId = null,
            createdByAgentId = createdByAgentId,
            createdAt = now,
            updatedAt = now,
        )

        // Persist ticket to repository
        val createResult = ticketRepository.createTicket(ticket)
        if (createResult.isFailure) {
            logger.logError(
                message = "Failed to create ticket: $title",
                throwable = createResult.exceptionOrNull(),
            )
            return Result.failure(
                createResult.exceptionOrNull() ?: TicketError.DatabaseError(Exception("Unknown error"))
            )
        }

        val createdTicket = createResult.getOrNull()
            ?: return Result.failure(TicketError.DatabaseError(Exception("Ticket creation returned null")))

        // Create a MessageThread for ticket discussion
        val thread = messageApi.createThread(
            participants = setOf(createdByAgentId),
            channel = MessageChannel.Public.Engineering,
            initialMessageContent = buildTicketCreatedMessage(createdTicket),
        )

        // Publish TicketCreated event
        eventBus.publish(
            TicketEvent.TicketCreated(
                eventId = randomUUID(),
                ticketId = createdTicket.id,
                title = createdTicket.title,
                description = createdTicket.description,
                type = createdTicket.type,
                priority = createdTicket.priority,
                createdBy = createdByAgentId,
                timestamp = now,
                urgency = priorityToUrgency(createdTicket.priority),
            )
        )

        return Result.success(createdTicket to thread)
    }

    /**
     * Transition a ticket to a new status after validating the actor has permission.
     * Publishes a TicketStatusChanged event and posts a status update message to the ticket's thread.
     *
     * @param ticketId The ID of the ticket to transition.
     * @param newStatus The target status to transition to.
     * @param actorAgentId The agent performing the transition (must be assigned or creator).
     * @return Result containing the updated ticket.
     */
    suspend fun transitionTicketStatus(
        ticketId: TicketId,
        newStatus: TicketStatus,
        actorAgentId: AgentId,
    ): Result<Ticket> {
        // Retrieve current ticket
        val ticketResult = ticketRepository.getTicket(ticketId)
        if (ticketResult.isFailure) {
            return Result.failure(
                ticketResult.exceptionOrNull() ?: TicketError.TicketNotFound(ticketId)
            )
        }

        val currentTicket = ticketResult.getOrNull()
            ?: return Result.failure(TicketError.TicketNotFound(ticketId))

        // Validate actor has permission (must be assigned agent or creator)
        if (!hasPermission(currentTicket, actorAgentId)) {
            return Result.failure(
                TicketError.ValidationError(
                    "Agent $actorAgentId does not have permission to modify ticket $ticketId. " +
                        "Only the assigned agent or creator can modify this ticket."
                )
            )
        }

        // Validate state transition
        if (!currentTicket.canTransitionTo(newStatus)) {
            return Result.failure(
                TicketError.InvalidStateTransition(
                    fromState = currentTicket.status,
                    toState = newStatus,
                )
            )
        }

        val previousStatus = currentTicket.status
        val now = Clock.System.now()

        // Update ticket status in repository
        val updateResult = ticketRepository.updateStatus(ticketId, newStatus)
        if (updateResult.isFailure) {
            logger.logError(
                message = "Failed to update ticket status: $ticketId",
                throwable = updateResult.exceptionOrNull(),
            )
            return Result.failure(
                updateResult.exceptionOrNull() ?: TicketError.DatabaseError(Exception("Unknown error"))
            )
        }

        // Get updated ticket
        val updatedTicketResult = ticketRepository.getTicket(ticketId)
        val updatedTicket = updatedTicketResult.getOrNull()
            ?: return Result.failure(TicketError.TicketNotFound(ticketId))

        // Publish TicketStatusChanged event
        eventBus.publish(
            TicketEvent.TicketStatusChanged(
                eventId = randomUUID(),
                ticketId = ticketId,
                previousStatus = previousStatus,
                newStatus = newStatus,
                changedBy = actorAgentId,
                timestamp = now,
                urgency = priorityToUrgency(updatedTicket.priority),
            )
        )

        // Post status update message to ticket thread
        getOrCreateTicketThread(updatedTicket)?.let { thread ->
            // If transitioning from BLOCKED, reopen the thread to allow posting messages
            // (the thread is in WAITING_FOR_HUMAN status after blocking)
            if (previousStatus == TicketStatus.BLOCKED) {
                messageApi.reopenThread(thread.id)
            }

            messageApi.postMessage(
                threadId = thread.id,
                content = buildStatusChangedMessage(updatedTicket, previousStatus, newStatus, actorAgentId),
            )
        }

        return Result.success(updatedTicket)
    }

    /**
     * Assign a ticket to an agent. Publishes a TicketAssigned event and posts
     * an assignment notification to the ticket's thread.
     *
     * @param ticketId The ID of the ticket to assign.
     * @param targetAgentId The agent to assign the ticket to (null to unassign).
     * @param assignerAgentId The agent making the assignment (must be assigned or creator).
     * @return Result containing the updated ticket.
     */
    suspend fun assignTicket(
        ticketId: TicketId,
        targetAgentId: AgentId?,
        assignerAgentId: AgentId,
    ): Result<Ticket> {
        // Retrieve current ticket
        val ticketResult = ticketRepository.getTicket(ticketId)
        if (ticketResult.isFailure) {
            return Result.failure(
                ticketResult.exceptionOrNull() ?: TicketError.TicketNotFound(ticketId)
            )
        }

        val currentTicket = ticketResult.getOrNull()
            ?: return Result.failure(TicketError.TicketNotFound(ticketId))

        // Validate assigner has permission (must be assigned agent or creator)
        if (!hasPermission(currentTicket, assignerAgentId)) {
            return Result.failure(
                TicketError.ValidationError(
                    "Agent $assignerAgentId does not have permission to assign ticket $ticketId. " +
                        "Only the assigned agent or creator can assign this ticket."
                )
            )
        }

        val now = Clock.System.now()

        // Update ticket assignment in repository
        val assignResult = ticketRepository.assignTicket(ticketId, targetAgentId)
        if (assignResult.isFailure) {
            logger.logError(
                message = "Failed to assign ticket: $ticketId",
                throwable = assignResult.exceptionOrNull(),
            )
            return Result.failure(
                assignResult.exceptionOrNull() ?: TicketError.DatabaseError(Exception("Unknown error"))
            )
        }

        // Get updated ticket
        val updatedTicketResult = ticketRepository.getTicket(ticketId)
        val updatedTicket = updatedTicketResult.getOrNull()
            ?: return Result.failure(TicketError.TicketNotFound(ticketId))

        // Publish TicketAssigned event
        eventBus.publish(
            TicketEvent.TicketAssigned(
                eventId = randomUUID(),
                ticketId = ticketId,
                assignedTo = targetAgentId,
                assignedBy = assignerAgentId,
                timestamp = now,
                urgency = priorityToUrgency(updatedTicket.priority),
            )
        )

        // Post assignment notification to ticket thread
        getOrCreateTicketThread(updatedTicket)?.let { thread ->
            messageApi.postMessage(
                threadId = thread.id,
                content = buildAssignmentMessage(updatedTicket, targetAgentId, assignerAgentId),
            )
        }

        return Result.success(updatedTicket)
    }

    /**
     * Block a ticket with a reason. Transitions to BLOCKED status, publishes a TicketBlocked event,
     * and creates an escalation message in the ticket thread requesting human intervention.
     *
     * @param ticketId The ID of the ticket to block.
     * @param blockingReason The reason the ticket is blocked.
     * @param reportedByAgentId The agent reporting the blocker.
     * @return Result containing the updated ticket.
     */
    suspend fun blockTicket(
        ticketId: TicketId,
        blockingReason: String,
        reportedByAgentId: AgentId,
    ): Result<Ticket> {
        // Retrieve current ticket
        val ticketResult = ticketRepository.getTicket(ticketId)
        if (ticketResult.isFailure) {
            return Result.failure(
                ticketResult.exceptionOrNull() ?: TicketError.TicketNotFound(ticketId)
            )
        }

        val currentTicket = ticketResult.getOrNull()
            ?: return Result.failure(TicketError.TicketNotFound(ticketId))

        // Validate ticket can transition to BLOCKED
        if (!currentTicket.canTransitionTo(TicketStatus.BLOCKED)) {
            return Result.failure(
                TicketError.InvalidStateTransition(
                    fromState = currentTicket.status,
                    toState = TicketStatus.BLOCKED,
                )
            )
        }

        val now = Clock.System.now()

        // Update ticket status to BLOCKED
        val updateResult = ticketRepository.updateStatus(ticketId, TicketStatus.BLOCKED)
        if (updateResult.isFailure) {
            logger.logError(
                message = "Failed to block ticket: $ticketId",
                throwable = updateResult.exceptionOrNull(),
            )
            return Result.failure(
                updateResult.exceptionOrNull() ?: TicketError.DatabaseError(Exception("Unknown error"))
            )
        }

        // Get updated ticket
        val updatedTicketResult = ticketRepository.getTicket(ticketId)
        val updatedTicket = updatedTicketResult.getOrNull()
            ?: return Result.failure(TicketError.TicketNotFound(ticketId))

        // Publish TicketBlocked event
        eventBus.publish(
            TicketEvent.TicketBlocked(
                eventId = randomUUID(),
                ticketId = ticketId,
                blockingReason = blockingReason,
                reportedBy = reportedByAgentId,
                timestamp = now,
                urgency = Urgency.HIGH,
            )
        )

        // Create escalation message in ticket thread requesting human intervention
        getOrCreateTicketThread(updatedTicket)?.let { thread ->
            messageApi.escalateToHuman(
                threadId = thread.id,
                reason = "Ticket blocked: $blockingReason",
                context = mapOf(
                    "ticketId" to ticketId,
                    "ticketTitle" to updatedTicket.title,
                    "reportedBy" to reportedByAgentId,
                    "priority" to updatedTicket.priority.name,
                ),
            )
        }

        return Result.success(updatedTicket)
    }

    // ==================== Helper Methods ====================

    /**
     * Retrieve the MessageThread associated with a ticket by searching for threads
     * with matching title created at ticket creation time, or creates one if missing.
     */
    private suspend fun getOrCreateTicketThread(ticket: Ticket): MessageThread? {
        // Search for existing thread by looking at all threads
        // In a production system, we would store the thread ID with the ticket
        val allThreadsResult = messageApi.getAllThreads()
        if (allThreadsResult.isFailure) {
            logger.logError(
                message = "Failed to retrieve threads for ticket: ${ticket.id}",
                throwable = allThreadsResult.exceptionOrNull(),
            )
            return null
        }

        val allThreads = allThreadsResult.getOrNull() ?: emptyList()

        // Find thread that matches ticket title in initial message content
        val existingThread = allThreads.find { thread ->
            thread.messages.firstOrNull()?.content?.contains(ticket.title) == true
        }

        if (existingThread != null) {
            return existingThread
        }

        // Create a new thread if not found
        val participants = buildSet {
            add(ticket.createdByAgentId)
            ticket.assignedAgentId?.let { add(it) }
        }

        return messageApi.createThread(
            participants = participants,
            channel = MessageChannel.Public.Engineering,
            initialMessageContent = buildTicketCreatedMessage(ticket),
        )
    }

    /**
     * Check if an agent has permission to modify a ticket.
     * Permission is granted if the agent is the creator or currently assigned.
     */
    private fun hasPermission(ticket: Ticket, agentId: AgentId): Boolean {
        return agentId == ticket.createdByAgentId || agentId == ticket.assignedAgentId
    }

    /**
     * Convert ticket priority to event urgency.
     */
    private fun priorityToUrgency(priority: TicketPriority): Urgency = when (priority) {
        TicketPriority.LOW -> Urgency.LOW
        TicketPriority.MEDIUM -> Urgency.MEDIUM
        TicketPriority.HIGH, TicketPriority.CRITICAL -> Urgency.HIGH
    }

    // ==================== Message Builders ====================

    private fun buildTicketCreatedMessage(ticket: Ticket): String {
        return buildString {
            append("Ticket Created: ${ticket.title}\n\n")
            append("Type: ${ticket.type.name}\n")
            append("Priority: ${ticket.priority.name}\n")
            append("Status: ${ticket.status.name}\n")
            append("Created by: ${ticket.createdByAgentId}\n\n")
            if (ticket.description.isNotBlank()) {
                append("Description:\n${ticket.description}")
            }
        }
    }

    private fun buildStatusChangedMessage(
        ticket: Ticket,
        previousStatus: TicketStatus,
        newStatus: TicketStatus,
        changedBy: AgentId,
    ): String {
        return "[${ticket.id}] Status changed: ${previousStatus.name} → ${newStatus.name} by $changedBy"
    }

    private fun buildAssignmentMessage(
        ticket: Ticket,
        assignedTo: AgentId?,
        assignedBy: AgentId,
    ): String {
        return if (assignedTo != null) {
            "[${ticket.id}] Ticket assigned to @$assignedTo by $assignedBy"
        } else {
            "[${ticket.id}] Ticket unassigned by $assignedBy"
        }
    }
}
