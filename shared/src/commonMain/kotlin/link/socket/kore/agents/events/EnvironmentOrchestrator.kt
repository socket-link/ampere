package link.socket.kore.agents.events

import link.socket.kore.agents.events.api.AgentEventApi
import link.socket.kore.agents.events.bus.EventBus
import link.socket.kore.agents.events.meetings.Meeting
import link.socket.kore.agents.events.meetings.MeetingOrchestrator
import link.socket.kore.agents.events.meetings.MeetingRepository
import link.socket.kore.agents.events.meetings.MeetingSchedulingService
import link.socket.kore.agents.events.messages.AgentMessageApi
import link.socket.kore.agents.events.tickets.TicketOrchestrator
import link.socket.kore.agents.events.tickets.TicketRepository
import link.socket.kore.agents.events.utils.ConsoleEventLogger
import link.socket.kore.agents.events.utils.EventLogger

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
    private val meetingRepository: MeetingRepository,
    private val ticketRepository: TicketRepository,
    private val eventBus: EventBus,
    private val messageApi: AgentMessageApi,
    private val eventApi: AgentEventApi,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /**
     * The meeting orchestrator that handles meeting lifecycle operations.
     */
    val meetingOrchestrator: MeetingOrchestrator = MeetingOrchestrator(
        repository = meetingRepository,
        eventBus = eventBus,
        messageApi = messageApi,
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
        eventBus = eventBus,
        messageApi = messageApi,
        meetingSchedulingService = createMeetingSchedulingService(),
        logger = logger,
    )

    /**
     * The event router that routes events to subscribed agents.
     */
    val eventRouter: EventRouter = EventRouter(
        eventApi = eventApi,
        eventBus = eventBus,
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
