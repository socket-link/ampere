package link.socket.ampere.agents.events.meetings

import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.task.MeetingTask
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.utils.generateUUID

class MeetingBuilder(
    private val agentId: AgentId,
) {

    private var type: MeetingType? = null
    private var title: String? = null
    private var scheduledFor: Instant? = null

    private val agendaItems: MutableList<MeetingTask.AgendaItem> = mutableListOf()
    private val participants: MutableList<AssignedTo> = mutableListOf()
    private val optionalParticipants: MutableList<AssignedTo> = mutableListOf()

    fun ofType(type: MeetingType): MeetingBuilder {
        this.type = type
        return this
    }

    fun withTitle(title: String): MeetingBuilder {
        this.title = title
        return this
    }

    fun addAgendaItem(
        topic: String,
        description: String? = null,
        assignedTo: AssignedTo.Agent? = null,
    ): MeetingBuilder {
        agendaItems.add(
            MeetingTask.AgendaItem(
                id = generateUUID(agentId, assignedTo?.agentId ?: ""),
                title = topic,
                description = description,
                assignedTo = assignedTo,
            ),
        )
        return this
    }

    fun addParticipant(
        participant: AssignedTo,
    ): MeetingBuilder {
        participants.add(participant)
        return this
    }

    fun addOptionalParticipant(
        participant: AssignedTo,
    ): MeetingBuilder {
        optionalParticipants.add(participant)
        return this
    }

    fun scheduledFor(
        scheduledFor: Instant,
    ): MeetingBuilder {
        this.scheduledFor = scheduledFor
        return this
    }

    fun buildMeeting(
        meetingTriggeredByEvent: Event? = null,
    ): Result<Meeting> =
        runCatching {
            requireNotNull(type)
            requireNotNull(title)
            requireNotNull(scheduledFor)
            require(participants.isNotEmpty())

            Meeting(
                id = generateUUID(agentId, meetingTriggeredByEvent?.eventId ?: ""),
                type = type!!,
                status = MeetingStatus.Scheduled(scheduledFor!!),
                invitation = MeetingInvitation(
                    title = title!!,
                    agenda = agendaItems,
                    requiredParticipants = participants,
                    optionalParticipants = optionalParticipants,
                    expectedOutcomes = null,
                ),
                creationTriggeredBy = meetingTriggeredByEvent,
            )
        }
}
