package link.socket.ampere.agents.events.meetings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.type.AutonomousAgent
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus

/**
 * Enables agents to subscribe to meeting events and automatically participate when required.
 * Routes meeting events to registered agents based on participant lists and handles
 * agent participation in meetings.
 */
class MeetingParticipationHandler(
    private val eventSerialBus: EventSerialBus,
    private val messageApi: link.socket.ampere.agents.events.messages.AgentMessageApi,
    private val meetingRepository: MeetingRepository,
) {
    // Map of agentId to their event handler
    private val agentHandlers: MutableMap<AgentId, suspend (MeetingEvent) -> Unit> = mutableMapOf()
    private val mutex = Mutex()

    /**
     * Registers an agent to receive meeting events.
     * The handler will be called when meeting events relevant to this agent occur.
     *
     * @param agentId The ID of the agent to register
     * @param handler The callback to invoke when meeting events occur
     */
    suspend fun subscribeAgent(
        agentId: AgentId,
        handler: suspend (MeetingEvent) -> Unit,
    ) {
        mutex.withLock {
            agentHandlers[agentId] = handler
        }
    }

    /**
     * Unregisters an agent from receiving meeting events.
     *
     * @param agentId The ID of the agent to unregister
     */
    suspend fun unsubscribeAgent(agentId: AgentId) {
        mutex.withLock {
            agentHandlers.remove(agentId)
        }
    }

    /**
     * Initialize the handler by subscribing to relevant meeting events from EventBus.
     * Should be called once during application startup.
     */
    fun initialize() {
        // Subscribe to MeetingStarted events
        eventSerialBus.subscribe(
            agentId = "meeting-participation-handler",
            eventType = MeetingEvent.MeetingStarted.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                if (event is MeetingEvent.MeetingStarted) {
                    handleMeetingStartedEvent(event)
                }
            },
        )

        // Subscribe to AgendaItemStarted events
        eventSerialBus.subscribe(
            agentId = "meeting-participation-handler",
            eventType = MeetingEvent.AgendaItemStarted.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                if (event is MeetingEvent.AgendaItemStarted) {
                    handleAgendaItemStartedEvent(event)
                }
            },
        )

        // Subscribe to MeetingCompleted events
        eventSerialBus.subscribe(
            agentId = "meeting-participation-handler",
            eventType = MeetingEvent.MeetingCompleted.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                if (event is MeetingEvent.MeetingCompleted) {
                    handleMeetingCompletedEvent(event)
                }
            },
        )
    }

    /**
     * Handles a MeetingStarted event by notifying all registered participant agents.
     */
    private suspend fun handleMeetingStartedEvent(event: MeetingEvent.MeetingStarted) {
        val meetingResult = meetingRepository.getMeeting(event.meetingId)
        if (meetingResult.isFailure) return

        val meeting = meetingResult.getOrNull() ?: return
        val participantAgentIds = getParticipantAgentIds(meeting)

        // Notify all registered agents that are participants
        for (agentId in participantAgentIds) {
            notifyAgent(agentId, event)
        }
    }

    /**
     * Handles an AgendaItemStarted event by notifying all registered participant agents.
     */
    private suspend fun handleAgendaItemStartedEvent(event: MeetingEvent.AgendaItemStarted) {
        val meetingResult = meetingRepository.getMeeting(event.meetingId)
        if (meetingResult.isFailure) return

        val meeting = meetingResult.getOrNull() ?: return
        val participantAgentIds = getParticipantAgentIds(meeting)

        // Notify all registered agents that are participants
        for (agentId in participantAgentIds) {
            notifyAgent(agentId, event)
        }
    }

    /**
     * Handles a MeetingCompleted event by notifying all registered participant agents.
     */
    private suspend fun handleMeetingCompletedEvent(event: MeetingEvent.MeetingCompleted) {
        val meetingResult = meetingRepository.getMeeting(event.meetingId)
        if (meetingResult.isFailure) return

        val meeting = meetingResult.getOrNull() ?: return
        val participantAgentIds = getParticipantAgentIds(meeting)

        // Notify all registered agents that are participants
        for (agentId in participantAgentIds) {
            notifyAgent(agentId, event)
        }
    }

    /**
     * Routes a meeting event to the registered handler for an agent.
     *
     * @param agentId The ID of the agent to notify
     * @param event The meeting event to deliver
     */
    private suspend fun notifyAgent(agentId: AgentId, event: MeetingEvent) {
        val handler = mutex.withLock {
            agentHandlers[agentId]
        }
        handler?.invoke(event)
    }

    /**
     * Handle a meeting start event for a specific agent.
     * Posts a message to the meeting thread announcing the agent's presence,
     * retrieves the current agenda item, and prompts the agent to participate.
     *
     * @param event The MeetingStarted event
     * @param agent The agent participating in the meeting
     */
    suspend fun handleMeetingStart(
        event: MeetingEvent.MeetingStarted,
        agent: AutonomousAgent<AgentState>,
    ) {
        // Post a message announcing agent's presence
        messageApi.postMessage(
            threadId = event.threadId,
            content = "${agent.id} has joined the meeting.",
        )

        // Retrieve current agenda items to find the first one
        val agendaItemsResult = meetingRepository.getAgendaItemsForMeeting(event.meetingId)
        if (agendaItemsResult.isFailure) return

        val agendaItems = agendaItemsResult.getOrNull() ?: emptyList()
        val currentItem = agendaItems.firstOrNull { it.status is TaskStatus.InProgress }
            ?: agendaItems.firstOrNull { it.status is TaskStatus.Pending }

        // Prompt agent to participate based on agenda topic
        if (currentItem != null) {
            val assignmentInfo = when {
                currentItem.assignedTo is AssignedTo.Agent &&
                    currentItem.assignedTo.agentId == agent.id ->
                    " You are assigned to present this topic."
                else -> ""
            }

            messageApi.postMessage(
                threadId = event.threadId,
                content = "@${agent.id} The current topic is: ${currentItem.title}.$assignmentInfo Please share your input.",
            )
        }
    }

    /**
     * Handle an agenda item start event for a specific agent.
     * Checks if the agent is assigned to this item and prompts accordingly.
     *
     * @param event The AgendaItemStarted event
     * @param agent The agent participating in the meeting
     */
    suspend fun handleAgendaItem(
        event: MeetingEvent.AgendaItemStarted,
        agent: AutonomousAgent<AgentState>,
    ) {
        // Get the meeting to find the thread ID
        val meetingResult = meetingRepository.getMeeting(event.meetingId)
        if (meetingResult.isFailure) return

        val meeting = meetingResult.getOrNull() ?: return
        val inProgressStatus = meeting.status as? MeetingStatus.InProgress ?: return
        val threadId = inProgressStatus.messagingDetails.messageThreadId

        // Check if agent is assigned to this agenda item
        val isAssigned = event.agendaItem.assignedTo is AssignedTo.Agent &&
            event.agendaItem.assignedTo.agentId == agent.id

        if (isAssigned) {
            // Agent is assigned - prompt to present/contribute on topic
            messageApi.postMessage(
                threadId = threadId,
                content = "@${agent.id} You are assigned to present: ${event.agendaItem.title}. Please share your update or findings.",
            )
        } else {
            // Agent is not assigned - prompt to listen and contribute as needed
            messageApi.postMessage(
                threadId = threadId,
                content = "@${agent.id} Now discussing: ${event.agendaItem.title}. Please contribute any relevant insights.",
            )
        }
    }

    /**
     * Check if an agent is a participant in a meeting.
     *
     * @param meetingId The ID of the meeting
     * @param agentId The ID of the agent
     * @return true if the agent is a participant, false otherwise
     */
    suspend fun isAgentParticipant(meetingId: String, agentId: AgentId): Boolean {
        val meetingResult = meetingRepository.getMeeting(meetingId)
        if (meetingResult.isFailure) return false

        val meeting = meetingResult.getOrNull() ?: return false
        return getParticipantAgentIds(meeting).contains(agentId)
    }

    /**
     * Gets the list of registered agent IDs.
     */
    suspend fun getRegisteredAgentIds(): Set<AgentId> {
        return mutex.withLock {
            agentHandlers.keys.toSet()
        }
    }

    // ==================== Helper Methods ====================

    private fun getParticipantAgentIds(meeting: Meeting): Set<AgentId> {
        return buildSet {
            meeting.invitation.requiredParticipants.forEach { participant ->
                when (participant) {
                    is AssignedTo.Agent -> add(participant.agentId)
                    is AssignedTo.Human -> { /* humans don't have agent IDs */ }
                    is AssignedTo.Team -> { /* teams are expanded to individual agents */ }
                }
            }
            meeting.invitation.optionalParticipants?.forEach { participant ->
                when (participant) {
                    is AssignedTo.Agent -> add(participant.agentId)
                    is AssignedTo.Human -> { /* humans don't have agent IDs */ }
                    is AssignedTo.Team -> { /* teams are expanded to individual agents */ }
                }
            }
        }
    }
}
