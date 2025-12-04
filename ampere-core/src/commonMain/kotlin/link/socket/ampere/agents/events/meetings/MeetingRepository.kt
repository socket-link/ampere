package link.socket.ampere.agents.events.meetings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.AgendaItemId
import link.socket.ampere.agents.domain.concept.task.MeetingTask
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.data.Repository
import link.socket.ampere.db.Database
import link.socket.ampere.db.meetings.MeetingStoreQueries

/**
 * Repository responsible for persisting and querying Meetings using SQLDelight.
 *
 * This handles conversion between domain models and database representations,
 * with JSON serialization for complex types like MeetingType, MeetingStatus, etc.
 */
class MeetingRepository(
    override val json: Json,
    override val scope: CoroutineScope,
    private val database: Database,
) : Repository<MeetingId, Meeting>(json, scope) {

    override val tag: String = "Meeting${super.tag}"

    private val queries: MeetingStoreQueries
        get() = database.meetingStoreQueries

    /**
     * Create a new meeting with all its related data (participants, agenda items).
     */
    suspend fun saveMeeting(meeting: Meeting): Result<Meeting> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = Clock.System.now().toEpochMilliseconds()

                // Insert the main meeting record
                queries.insertMeeting(
                    id = meeting.id,
                    title = meeting.invitation.title,
                    typeJson = encodeType(meeting.type),
                    statusType = getStatusTypeName(meeting.status),
                    statusJson = encodeStatus(meeting.status),
                    scheduledTime = meeting.getScheduledTime()?.toEpochMilliseconds(),
                    channelId = meeting.messagingDetails?.messageChannelId,
                    threadId = meeting.messagingDetails?.messageThreadId,
                    invitationJson = encodeInvitation(meeting.invitation),
                    triggeringEventId = meeting.creationTriggeredBy?.eventId,
                    createdAt = now,
                    updatedAt = now,
                )

                // Insert required participants
                meeting.invitation.requiredParticipants.forEach { participant ->
                    queries.insertParticipant(
                        meetingId = meeting.id,
                        participantId = getParticipantId(participant),
                        participantType = getParticipantType(participant),
                        isRequired = 1,
                    )
                }

                // Insert optional participants
                meeting.invitation.optionalParticipants?.forEach { participant ->
                    queries.insertParticipant(
                        meetingId = meeting.id,
                        participantId = getParticipantId(participant),
                        participantType = getParticipantType(participant),
                        isRequired = 0,
                    )
                }

                // Insert agenda items
                meeting.invitation.agenda.forEachIndexed { index, agendaItem ->
                    queries.insertAgendaItem(
                        id = agendaItem.id,
                        meetingId = meeting.id,
                        topic = agendaItem.title,
                        assignedTo = agendaItem.assignedTo?.getIdentifier(),
                        assignedToType = if (agendaItem.assignedTo != null) "AGENT" else null,
                        status = getTaskStatusName(agendaItem.status),
                        statusPayload = encodeTaskStatus(agendaItem.status),
                        orderIndex = index.toLong(),
                    )
                }

                // Insert outcomes if the meeting is completed or canceled
                meeting.outcomes.forEachIndexed { index, outcome ->
                    queries.insertOutcome(
                        id = outcome.id,
                        meetingId = meeting.id,
                        outcomeType = getOutcomeTypeName(outcome),
                        description = getOutcomeDescription(outcome),
                        outcomeJson = encodeOutcome(outcome),
                        createdAt = now + index,
                    )
                }

                // Insert attendees if the meeting has them
                when (val status = meeting.status) {
                    is MeetingStatus.Completed -> {
                        status.attendedBy.forEach { attendee ->
                            queries.insertAttendee(
                                meetingId = meeting.id,
                                attendeeId = attendee.getIdentifier(),
                                attendeeType = getEventSourceType(attendee),
                            )
                        }
                    }
                    else -> { /* No attendees for other statuses */ }
                }
            }.map { meeting }
        }

    /**
     * Retrieve a meeting by its ID, reconstructing the full domain model.
     */
    suspend fun getMeeting(meetingId: MeetingId): Result<Meeting?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val meetingRow = queries.getMeetingById(meetingId).executeAsOneOrNull()
                    ?: return@runCatching null

                // Retrieve related data
                val participantRows = queries.getParticipantsByMeetingId(meetingId).executeAsList()
                val agendaItemRows = queries.getAgendaItemsForMeeting(meetingId).executeAsList()
                val outcomeRows = queries.getOutcomesForMeeting(meetingId).executeAsList()
                val attendeeRows = queries.getAttendeesForMeeting(meetingId).executeAsList()

                // Reconstruct domain model
                val type = decodeType(meetingRow.typeJson)
                val agendaItems = agendaItemRows.map { row ->
                    MeetingTask.AgendaItem(
                        id = row.id,
                        title = row.topic,
                        status = decodeTaskStatus(row.status, row.statusPayload),
                        assignedTo = row.assignedTo?.let { AssignedTo.Agent(it) },
                    )
                }

                val requiredParticipants = participantRows
                    .filter { it.isRequired == 1L }
                    .map { decodeParticipant(it.participantId, it.participantType) }

                val optionalParticipants = participantRows
                    .filter { it.isRequired == 0L }
                    .map { decodeParticipant(it.participantId, it.participantType) }

                val invitation = MeetingInvitation(
                    title = meetingRow.title,
                    agenda = agendaItems,
                    requiredParticipants = requiredParticipants,
                    optionalParticipants = optionalParticipants.ifEmpty { null },
                )

                val outcomes = outcomeRows.map { row ->
                    decodeOutcome(row.id, row.outcomeType, row.description, row.outcomeJson)
                }

                val attendees = attendeeRows.map { row ->
                    decodeEventSource(row.attendeeId, row.attendeeType)
                }

                val status = decodeStatus(
                    statusType = meetingRow.statusType,
                    statusJson = meetingRow.statusJson,
                    channelId = meetingRow.channelId,
                    threadId = meetingRow.threadId,
                    outcomes = outcomes,
                    attendees = attendees,
                )

                Meeting(
                    id = meetingRow.id,
                    type = type,
                    status = status,
                    invitation = invitation,
                    creationTriggeredBy = null, // Event reconstruction not implemented
                )
            }
        }

    /**
     * Update the status of a meeting.
     */
    suspend fun updateMeetingStatus(meetingId: MeetingId, status: MeetingStatus): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = Clock.System.now().toEpochMilliseconds()
                val messagingDetails = when (status) {
                    is MeetingStatus.Scheduled,
                    is MeetingStatus.Delayed,
                    -> null
                    is MeetingStatus.InProgress -> status.messagingDetails
                    is MeetingStatus.Completed -> status.messagingDetails
                    is MeetingStatus.Canceled -> status.messagingDetails
                }

                queries.updateMeetingStatus(
                    statusType = getStatusTypeName(status),
                    statusJson = encodeStatus(status),
                    channelId = messagingDetails?.messageChannelId,
                    threadId = messagingDetails?.messageThreadId,
                    updatedAt = now,
                    id = meetingId,
                )

                // Insert attendees if completing the meeting
                if (status is MeetingStatus.Completed) {
                    // Clear existing attendees first
                    queries.deleteAttendeesForMeeting(meetingId)

                    status.attendedBy.forEach { attendee ->
                        queries.insertAttendee(
                            meetingId = meetingId,
                            attendeeId = attendee.getIdentifier(),
                            attendeeType = getEventSourceType(attendee),
                        )
                    }

                    // Insert outcomes
                    status.outcomes?.forEach { outcome ->
                        queries.insertOutcome(
                            id = outcome.id,
                            meetingId = meetingId,
                            outcomeType = getOutcomeTypeName(outcome),
                            description = getOutcomeDescription(outcome),
                            outcomeJson = encodeOutcome(outcome),
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                        )
                    }
                }
            }.map { }
        }

    /**
     * Add an outcome to a meeting.
     */
    suspend fun addOutcome(meetingId: MeetingId, outcome: MeetingOutcome): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.insertOutcome(
                    id = outcome.id,
                    meetingId = meetingId,
                    outcomeType = getOutcomeTypeName(outcome),
                    description = getOutcomeDescription(outcome),
                    outcomeJson = encodeOutcome(outcome),
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                )
            }.map { }
        }

    /**
     * Get all scheduled meetings before a given time.
     */
    suspend fun getScheduledMeetings(before: Instant): Result<List<Meeting>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.getMeetingsBeforeTime(before.toEpochMilliseconds())
                    .executeAsList()
                    .filter { it.statusType == "SCHEDULED" }
                    .mapNotNull { meetingRow ->
                        getMeeting(meetingRow.id).getOrNull()
                    }
            }
        }

    /**
     * Get all meetings for a participant.
     */
    suspend fun getMeetingsForParticipant(participantId: String): Result<List<Meeting>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.getMeetingsForParticipant(participantId)
                    .executeAsList()
                    .mapNotNull { meetingRow ->
                        getMeeting(meetingRow.id).getOrNull()
                    }
            }
        }

    /**
     * Delete a meeting and all related records.
     */
    suspend fun deleteMeeting(meetingId: MeetingId): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.deleteParticipantsForMeeting(meetingId)
                queries.deleteAgendaItemsForMeeting(meetingId)
                queries.deleteOutcomesForMeeting(meetingId)
                queries.deleteAttendeesForMeeting(meetingId)
                queries.deleteMeeting(meetingId)
            }.map { }
        }

    /**
     * Get all agenda items for a meeting.
     */
    suspend fun getAgendaItemsForMeeting(meetingId: MeetingId): Result<List<MeetingTask.AgendaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.getAgendaItemsForMeeting(meetingId)
                    .executeAsList()
                    .map { row ->
                        MeetingTask.AgendaItem(
                            id = row.id,
                            title = row.topic,
                            status = decodeTaskStatus(row.status, row.statusPayload),
                            assignedTo = row.assignedTo?.let { AssignedTo.Agent(it) },
                        )
                    }
            }
        }

    /**
     * Update the status of an agenda item.
     */
    suspend fun updateAgendaItemStatus(
        agendaItemId: AgendaItemId,
        status: TaskStatus,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.updateAgendaItemStatus(
                    status = getTaskStatusName(status),
                    statusPayload = encodeTaskStatus(status),
                    id = agendaItemId,
                )
            }.map { }
        }

    // ==================== Encoding Helpers ====================

    private fun encodeType(type: MeetingType): String = try {
        json.encodeToString(MeetingType.serializer(), type)
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to serialize MeetingType", e)
    }

    private fun encodeStatus(status: MeetingStatus): String = try {
        json.encodeToString(MeetingStatus.serializer(), status)
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to serialize MeetingStatus", e)
    }

    private fun encodeInvitation(invitation: MeetingInvitation): String = try {
        json.encodeToString(MeetingInvitation.serializer(), invitation)
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to serialize MeetingInvitation", e)
    }

    private fun encodeOutcome(outcome: MeetingOutcome): String = try {
        json.encodeToString(MeetingOutcome.serializer(), outcome)
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to serialize MeetingOutcome", e)
    }

    private fun encodeTaskStatus(status: TaskStatus): String? = try {
        when (status) {
            TaskStatus.Pending -> null
            TaskStatus.InProgress -> null
            is TaskStatus.Blocked -> json.encodeToString(status.reason)
            is TaskStatus.Completed -> json.encodeToString(TaskStatus.Completed.serializer(), status)
            TaskStatus.Deferred -> null
        }
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to serialize Task.Status", e)
    }

    // ==================== Decoding Helpers ====================

    private fun decodeType(typeJson: String): MeetingType = try {
        json.decodeFromString(MeetingType.serializer(), typeJson)
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to deserialize MeetingType", e)
    }

    private fun decodeStatus(
        statusType: String,
        statusJson: String,
        channelId: String?,
        threadId: String?,
        outcomes: List<MeetingOutcome>,
        attendees: List<EventSource>,
    ): MeetingStatus = try {
        // We decode the full status from JSON since it contains all necessary fields
        val baseStatus = json.decodeFromString(MeetingStatus.serializer(), statusJson)

        // For completed/canceled statuses, we need to inject the outcomes and attendees
        when (baseStatus) {
            is MeetingStatus.Completed -> baseStatus.copy(
                outcomes = outcomes.ifEmpty { null },
                attendedBy = attendees,
            )
            is MeetingStatus.Canceled -> baseStatus.copy(
                outcomes = outcomes.ifEmpty { null },
            )
            else -> baseStatus
        }
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to deserialize MeetingStatus", e)
    }

    private fun decodeOutcome(
        id: String,
        outcomeType: String,
        description: String,
        outcomeJson: String,
    ): MeetingOutcome = try {
        json.decodeFromString(MeetingOutcome.serializer(), outcomeJson)
    } catch (e: SerializationException) {
        throw MeetingSerializationException("Failed to deserialize MeetingOutcome: $outcomeJson", e)
    }

    private fun decodeTaskStatus(status: String, statusPayload: String?): TaskStatus {
        return when (status) {
            "PENDING" -> TaskStatus.Pending
            "IN_PROGRESS" -> TaskStatus.InProgress
            "BLOCKED" -> TaskStatus.Blocked(
                reason = statusPayload?.let {
                    try { json.decodeFromString<String>(it) } catch (e: Exception) { null }
                } ?: "Unknown reason",
            )
            "COMPLETED" -> statusPayload?.let { payload ->
                try {
                    json.decodeFromString(TaskStatus.Completed.serializer(), payload)
                } catch (e: Exception) {
                    TaskStatus.Completed(
                        completedAt = Clock.System.now(),
                        completedBy = EventSource.Human,
                    )
                }
            } ?: TaskStatus.Completed(
                completedAt = Clock.System.now(),
                completedBy = EventSource.Human,
            )
            "DEFERRED" -> TaskStatus.Deferred
            else -> TaskStatus.Pending
        }
    }

    private fun decodeParticipant(participantId: String, participantType: String): AssignedTo {
        return when (participantType) {
            "AGENT" -> AssignedTo.Agent(participantId)
            "HUMAN" -> AssignedTo.Human
            "TEAM" -> AssignedTo.Team(participantId)
            else -> AssignedTo.Agent(participantId)
        }
    }

    private fun decodeEventSource(id: String, type: String): EventSource {
        return when (type) {
            "AGENT" -> EventSource.Agent(id)
            "HUMAN" -> EventSource.Human
            else -> EventSource.Agent(id)
        }
    }

    // ==================== Helper Methods ====================

    private fun getStatusTypeName(status: MeetingStatus): String = when (status) {
        is MeetingStatus.Scheduled -> "SCHEDULED"
        is MeetingStatus.Delayed -> "DELAYED"
        is MeetingStatus.InProgress -> "IN_PROGRESS"
        is MeetingStatus.Completed -> "COMPLETED"
        is MeetingStatus.Canceled -> "CANCELED"
    }

    private fun getTaskStatusName(status: TaskStatus): String = when (status) {
        is TaskStatus.Pending -> "PENDING"
        is TaskStatus.InProgress -> "IN_PROGRESS"
        is TaskStatus.Blocked -> "BLOCKED"
        is TaskStatus.Completed -> "COMPLETED"
        is TaskStatus.Deferred -> "DEFERRED"
    }

    private fun getOutcomeTypeName(outcome: MeetingOutcome): String = when (outcome) {
        is MeetingOutcome.BlockerRaised -> "BLOCKER_RAISED"
        is MeetingOutcome.GoalCreated -> "GOAL_CREATED"
        is MeetingOutcome.DecisionMade -> "DECISION_MADE"
        is MeetingOutcome.ActionItem -> "ACTION_ITEM"
    }

    private fun getOutcomeDescription(outcome: MeetingOutcome): String = when (outcome) {
        is MeetingOutcome.BlockerRaised -> outcome.description
        is MeetingOutcome.GoalCreated -> outcome.description
        is MeetingOutcome.DecisionMade -> outcome.description
        is MeetingOutcome.ActionItem -> outcome.description
    }

    private fun getParticipantId(participant: AssignedTo): String = when (participant) {
        is AssignedTo.Agent -> participant.agentId
        is AssignedTo.Human -> "human"
        is AssignedTo.Team -> participant.teamId
    }

    private fun getParticipantType(participant: AssignedTo): String = when (participant) {
        is AssignedTo.Agent -> "AGENT"
        is AssignedTo.Human -> "HUMAN"
        is AssignedTo.Team -> "TEAM"
    }

    private fun getEventSourceType(source: EventSource): String = when (source) {
        is EventSource.Agent -> "AGENT"
        is EventSource.Human -> "HUMAN"
    }

    private fun Meeting.getScheduledTime(): Instant? = when (val status = this.status) {
        is MeetingStatus.Scheduled -> status.scheduledFor
        is MeetingStatus.Delayed -> status.scheduledFor
        else -> null
    }
}

/**
 * Exception thrown when meeting serialization/deserialization fails.
 */
class MeetingSerializationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
