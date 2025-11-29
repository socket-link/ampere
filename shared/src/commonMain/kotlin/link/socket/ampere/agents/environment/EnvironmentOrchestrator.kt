package link.socket.ampere.agents.environment

import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.memory.OutcomeMemoryRepository
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.EventRouter
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.meetings.AgentMeetingsApiFactory
import link.socket.ampere.agents.events.meetings.Meeting
import link.socket.ampere.agents.events.meetings.MeetingOrchestrator
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.AgentMessageApiFactory
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.agents.events.meetings.MeetingSchedulingService

/**
 * Top-level orchestrator that coordinates all domain orchestrators.
 *
 * This class manages the lifecycle and dependencies of:
 * - MeetingOrchestrator: Handles meeting scheduling and lifecycle
 * - TicketOrchestrator: Handles ticket creation and workflow
 * - EventRouter: Routes events between subscribed agents
 *
 * By centralizing orchestrator management here, we avoid circular dependencies
 * between orchestrators while allowing them to communicate with each other
 * through this parent. For example, TicketOrchestrator can schedule meetings
 * without depending directly on MeetingOrchestrator.
 */
class EnvironmentOrchestrator(
    val meetingRepository: MeetingRepository,
    val meetingApiFactory: AgentMeetingsApiFactory,
    val ticketRepository: TicketRepository,
    val messageRepository: MessageRepository,
    val messageApiFactory: AgentMessageApiFactory,
    val eventRepository: EventRepository,
    val eventApiFactory: AgentEventApiFactory,
    val outcomeMemoryRepository: OutcomeMemoryRepository,
    val eventSerialBus: EventSerialBus,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    companion object {
        /**
         * System-level agent ID for orchestrator operations.
         * Used when the orchestrator needs to perform operations that require an agent context.
         */
        private const val SYSTEM_AGENT_ID: AgentId = "SYSTEM_ORCHESTRATOR"
    }

    /**
     * System-level message API for internal orchestrator operations.
     */
    private val systemMessageApi: AgentMessageApi = messageApiFactory.create(SYSTEM_AGENT_ID)

    /**
     * System-level event API for internal orchestrator operations.
     */
    private val systemEventApi: AgentEventApi = eventApiFactory.create(SYSTEM_AGENT_ID)

    /**
     * The meeting orchestrator that handles meeting lifecycle operations.
     */
    val meetingOrchestrator: MeetingOrchestrator = MeetingOrchestrator(
        repository = meetingRepository,
        eventSerialBus = eventSerialBus,
        messageApi = systemMessageApi,
        logger = logger,
    )

    /**
     * The ticket orchestrator that handles ticket lifecycle operations.
     *
     * Uses a MeetingSchedulingService that delegates to the meetingOrchestrator,
     * allowing TicketOrchestrator to schedule meetings without direct dependency.
     */
    val ticketOrchestrator: TicketOrchestrator = TicketOrchestrator(
        ticketRepository = ticketRepository,
        eventSerialBus = eventSerialBus,
        messageApi = systemMessageApi,
        meetingSchedulingService = createMeetingSchedulingService(),
        logger = logger,
    )

    /**
     * The event router that routes events to subscribed agents.
     */
    val eventRouter: EventRouter = EventRouter(
        eventApi = systemEventApi,
        eventSerialBus = eventSerialBus,
    )

    /**
     * Start all orchestrator services.
     *
     * This starts the event routing system. Individual orchestrators don't need
     * explicit startup as they respond to method calls.
     */
    fun start() {
        eventRouter.startRouting()
    }

    /**
     * Creates a MeetingSchedulingService that delegates to the meetingOrchestrator.
     *
     * This enables TicketOrchestrator to schedule meetings without directly
     * depending on MeetingOrchestrator, allowing for potential bidirectional
     * communication in the future where MeetingOrchestrator could also
     * interact with TicketOrchestrator.
     */
    private fun createMeetingSchedulingService(): MeetingSchedulingService {
        return MeetingSchedulingService { meeting: Meeting, scheduledBy: EventSource ->
            meetingOrchestrator.scheduleMeeting(meeting, scheduledBy)
        }
    }
}
