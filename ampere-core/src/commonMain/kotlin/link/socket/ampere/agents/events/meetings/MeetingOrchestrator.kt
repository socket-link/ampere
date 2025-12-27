package link.socket.ampere.agents.events.meetings

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.task.MeetingTask
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.util.randomUUID

/**
 * Service layer that coordinates meeting lifecycle operations, integrates with EventBus
 * for event publishing, and handles MessageThread creation for meeting discussions.
 */
class MeetingOrchestrator(
    private val repository: MeetingRepository,
    private val eventSerialBus: EventSerialBus,
    private val messageApi: AgentMessageApi,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /**
     * Schedule a new meeting by creating a message thread, persisting the meeting,
     * and publishing a MeetingScheduled event.
     *
     * @param meeting The meeting to schedule
     * @param scheduledBy The agent ID of the scheduler
     * @return Result containing the scheduled meeting with updated thread info
     */
    suspend fun scheduleMeeting(
        meeting: Meeting,
        scheduledBy: EventSource,
    ): Result<Meeting> {
        // Validate meeting data
        if (meeting.status !is MeetingStatus.Scheduled) {
            return Result.failure(
                IllegalArgumentException("Meeting must have Scheduled status to be scheduled"),
            )
        }

        val scheduledTime = meeting.status.scheduledFor

        val now = Clock.System.now()
        if (scheduledTime <= now) {
            return Result.failure(
                IllegalArgumentException(
                    "Meeting must be scheduled for a future time. Scheduled: $scheduledTime, Now: $now",
                ),
            )
        }

        if (meeting.invitation.requiredParticipants.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("Meeting must have at least one required participant"),
            )
        }

        // Create participants set from meeting invitation
        val participants = buildSet {
            meeting.invitation.requiredParticipants.forEach { participant ->
                when (participant) {
                    is AssignedTo.Agent -> add(participant.agentId)
                    is AssignedTo.Human -> { /* humans don't have message sender IDs */ }
                    is AssignedTo.Team -> { /* teams are expanded to individual agents */ }
                }
            }
            meeting.invitation.optionalParticipants?.forEach { participant ->
                when (participant) {
                    is AssignedTo.Agent -> add(participant.agentId)
                    is AssignedTo.Human -> { /* humans don't have message sender IDs */ }
                    is AssignedTo.Team -> { /* teams are expanded to individual agents */ }
                }
            }
        }

        // Create a new MessageThread for the meeting discussion
        val thread = messageApi.createThread(
            participants = participants,
            channel = MessageChannel.Public.Engineering, // Default to engineering channel for meetings
            initialMessageContent = buildScheduledMessage(meeting),
        )

        // Update meeting with thread information and scheduling
        val updatedMeeting = meeting.copy(
            messagingDetails = MeetingMessagingDetails(
                messageChannelId = thread.channel.getIdentifier(),
                messageThreadId = thread.id,
            ),
            status = MeetingStatus.Scheduled(
                scheduledFor = scheduledTime,
            ),
        )

        // Persist meeting via repository
        val createdMeetingResult = repository
            .saveMeeting(updatedMeeting)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to persist meeting ${meeting.id}",
                    throwable = throwable,
                )
                return Result.failure(throwable)
            }

        val createdMeeting = createdMeetingResult.getOrNull()
        requireNotNull(createdMeeting) { "Failed to persist meeting ${meeting.id}" }

        // Publish MeetingScheduled event
        eventSerialBus.publish(
            MeetingEvent.MeetingScheduled(
                eventId = generateUUID(createdMeeting.id),
                meeting = createdMeeting,
                eventSource = scheduledBy,
            ),
        )

        return createdMeetingResult
    }

    /**
     * Start a scheduled meeting by updating its status to IN_PROGRESS,
     * publishing a MeetingStarted event, and posting a system message.
     *
     * @param meetingId The ID of the meeting to start
     * @return Result indicating success or failure
     */
    suspend fun startMeeting(meetingId: String): Result<Unit> {
        // Retrieve meeting from repository
        val meetingResult = repository.getMeeting(meetingId)
        if (meetingResult.isFailure) {
            return Result.failure(meetingResult.exceptionOrNull() ?: Exception("Failed to get meeting"))
        }

        val meeting = meetingResult.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Meeting not found: $meetingId"))

        // Validate meeting is in SCHEDULED status
        if (meeting.status !is MeetingStatus.Scheduled) {
            return Result.failure(
                IllegalStateException(
                    "Meeting must be in SCHEDULED status to start. Current status: ${meeting.status::class.simpleName}",
                ),
            )
        }

        val now = Clock.System.now()

        // Create a thread for the meeting if not already created during scheduling
        // We use the messageApi's agentId as the orchestrator
        val participants = buildSet {
            meeting.invitation.requiredParticipants.forEach { participant ->
                when (participant) {
                    is AssignedTo.Agent -> add(participant.agentId)
                    is AssignedTo.Human -> { /* humans don't have message sender IDs */ }
                    is AssignedTo.Team -> { /* teams are expanded to individual agents */ }
                }
            }
        }

        val thread = messageApi.createThread(
            participants = participants,
            channel = MessageChannel.Public.Engineering,
            initialMessageContent = buildStartedMessage(meeting),
        )

        // Update status to IN_PROGRESS
        val inProgressStatus = MeetingStatus.InProgress(
            startedAt = now,
            messagingDetails = MeetingMessagingDetails(
                messageChannelId = thread.channel.getIdentifier(),
                messageThreadId = thread.id,
            ),
        )

        val updateResult = repository.updateMeetingStatus(meetingId, inProgressStatus)
        if (updateResult.isFailure) {
            return Result.failure(updateResult.exceptionOrNull() ?: Exception("Failed to update meeting status"))
        }

        // Publish MeetingStarted event
        eventSerialBus.publish(
            MeetingEvent.MeetingStarted(
                eventId = randomUUID(),
                meetingId = meetingId,
                threadId = thread.id,
                eventSource = EventSource.Agent(messageApi.agentId),
                timestamp = now,
            ),
        )

        return Result.success(Unit)
    }

    /**
     * Advance the meeting agenda by finding the next PENDING agenda item,
     * updating its status to IN_PROGRESS, and publishing an AgendaItemStarted event.
     *
     * @param meetingId The ID of the meeting
     * @return Result containing the next agenda item, or null if all are complete
     */
    suspend fun advanceAgenda(meetingId: String): Result<MeetingTask.AgendaItem?> {
        // Retrieve meeting to validate it exists and is in progress
        val meetingResult = repository.getMeeting(meetingId)
        if (meetingResult.isFailure) {
            return Result.failure(meetingResult.exceptionOrNull() ?: Exception("Failed to get meeting"))
        }

        val meeting = meetingResult.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Meeting not found: $meetingId"))

        if (meeting.status !is MeetingStatus.InProgress) {
            return Result.failure(
                IllegalStateException(
                    "Meeting must be in IN_PROGRESS status to advance agenda. Current status: ${meeting.status::class.simpleName}",
                ),
            )
        }

        // Get agenda items for the meeting
        val agendaItemsResult = repository.getAgendaItemsForMeeting(meetingId)
        if (agendaItemsResult.isFailure) {
            return Result.failure(agendaItemsResult.exceptionOrNull() ?: Exception("Failed to get agenda items"))
        }

        val agendaItems = agendaItemsResult.getOrNull() ?: emptyList()

        // Find the next PENDING agenda item
        val nextItem = agendaItems.find { it.status is TaskStatus.Pending }
            ?: return Result.success(null) // All items are complete

        // Update its status to IN_PROGRESS
        val updateResult = repository.updateAgendaItemStatus(
            agendaItemId = nextItem.id,
            status = TaskStatus.InProgress,
        )
        if (updateResult.isFailure) {
            return Result.failure(updateResult.exceptionOrNull() ?: Exception("Failed to update agenda item status"))
        }

        // Create the updated agenda item
        val updatedItem = nextItem.copy(status = TaskStatus.InProgress)

        // Publish AgendaItemStarted event
        eventSerialBus.publish(
            MeetingEvent.AgendaItemStarted(
                eventId = randomUUID(),
                meetingId = meetingId,
                agendaItem = updatedItem,
                eventSource = EventSource.Agent(messageApi.agentId),
                timestamp = Clock.System.now(),
            ),
        )

        // Post a message to the meeting thread about the agenda item
        messageApi.postMessage(
            threadId = meeting.status.messagingDetails.messageThreadId,
            content = "Now discussing: ${nextItem.title}${nextItem.assignedTo?.let { " (assigned to ${it.getIdentifier()})" } ?: ""}",
        )

        return Result.success(updatedItem)
    }

    /**
     * Complete a meeting by updating its status to COMPLETED, persisting outcomes,
     * publishing a MeetingCompleted event, and posting a summary message.
     *
     * @param meetingId The ID of the meeting to complete
     * @param outcomes The outcomes produced by the meeting
     * @return Result indicating success or failure
     */
    suspend fun completeMeeting(
        meetingId: String,
        outcomes: List<MeetingOutcome>,
    ): Result<Unit> {
        // Retrieve meeting from repository
        val meetingResult = repository.getMeeting(meetingId)
        if (meetingResult.isFailure) {
            return Result.failure(meetingResult.exceptionOrNull() ?: Exception("Failed to get meeting"))
        }

        val meeting = meetingResult.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Meeting not found: $meetingId"))

        // Validate meeting is in IN_PROGRESS status
        val inProgressStatus = meeting.status as? MeetingStatus.InProgress
            ?: return Result.failure(
                IllegalStateException(
                    "Meeting must be in IN_PROGRESS status to complete. Current status: ${meeting.status::class.simpleName}",
                ),
            )

        val now = Clock.System.now()

        // Build list of attendees (for now, use the required participants as attendees)
        val attendees = meeting.invitation.requiredParticipants.mapNotNull { participant ->
            when (participant) {
                is AssignedTo.Agent -> EventSource.Agent(participant.agentId)
                is AssignedTo.Human -> EventSource.Human
                is AssignedTo.Team -> null // Teams are not individual attendees
            }
        }

        // Update status to COMPLETED
        val completedStatus = MeetingStatus.Completed(
            completedAt = now,
            attendedBy = attendees,
            messagingDetails = inProgressStatus.messagingDetails,
            outcomes = outcomes.ifEmpty { null },
        )

        val updateResult = repository.updateMeetingStatus(meetingId, completedStatus)
        if (updateResult.isFailure) {
            return Result.failure(updateResult.exceptionOrNull() ?: Exception("Failed to update meeting status"))
        }

        // Publish MeetingCompleted event
        eventSerialBus.publish(
            MeetingEvent.MeetingCompleted(
                eventId = randomUUID(),
                meetingId = meetingId,
                outcomes = outcomes,
                eventSource = EventSource.Agent(messageApi.agentId),
                timestamp = now,
            ),
        )

        // Post summary message to the meeting thread
        val summaryMessage = buildCompletedMessage(meeting, outcomes)
        messageApi.postMessage(
            threadId = inProgressStatus.messagingDetails.messageThreadId,
            content = summaryMessage,
        )

        return Result.success(Unit)
    }

    // ==================== Helper Methods ====================

    private fun buildScheduledMessage(meeting: Meeting): String {
        val scheduledTime = (meeting.status as? MeetingStatus.Scheduled)?.scheduledFor
        return buildString {
            append("Meeting scheduled: ${meeting.invitation.title}\n")
            scheduledTime?.let { append("Time: $it\n") }
            append("Agenda:\n")
            meeting.invitation.agenda.forEachIndexed { index, item ->
                append("${index + 1}. ${item.title}")
                item.assignedTo?.let { append(" (${it.getIdentifier()})") }
                append("\n")
            }
        }
    }

    private fun buildStartedMessage(meeting: Meeting): String {
        return buildString {
            append("Meeting started: ${meeting.invitation.title}\n")
            append("Participants: ")
            append(
                meeting.invitation.requiredParticipants.joinToString(", ") {
                    when (it) {
                        is AssignedTo.Agent -> it.agentId
                        is AssignedTo.Human -> "human"
                        is AssignedTo.Team -> it.teamId
                    }
                },
            )
            append("\n\nAgenda:\n")
            meeting.invitation.agenda.forEachIndexed { index, item ->
                append("${index + 1}. ${item.title}\n")
            }
        }
    }

    private fun buildCompletedMessage(
        meeting: Meeting,
        outcomes: List<MeetingOutcome>,
    ): String {
        return buildString {
            append("Meeting completed: ${meeting.invitation.title}\n\n")

            if (outcomes.isNotEmpty()) {
                append("Outcomes:\n")
                outcomes.forEach { outcome ->
                    when (outcome) {
                        is MeetingOutcome.DecisionMade -> {
                            append("- Decision: ${outcome.description}\n")
                        }
                        is MeetingOutcome.ActionItem -> {
                            val assignee = when (val assigned = outcome.assignedTo) {
                                is AssignedTo.Agent -> assigned.agentId
                                is AssignedTo.Human -> "human"
                                is AssignedTo.Team -> assigned.teamId
                            }
                            append("- Action Item: ${outcome.description} (assigned to $assignee)\n")
                        }
                        is MeetingOutcome.BlockerRaised -> {
                            append("- Blocker: ${outcome.description}\n")
                        }
                        is MeetingOutcome.GoalCreated -> {
                            append("- Goal: ${outcome.description}\n")
                        }
                    }
                }
            } else {
                append("No formal outcomes recorded.\n")
            }
        }
    }
}
