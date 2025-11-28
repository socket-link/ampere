package link.socket.ampere.agents.environment

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.meetings.AgentMeetingsApiFactory
import link.socket.ampere.agents.events.meetings.MeetingOrchestrator
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.AgentMessageApiFactory
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.db.Database

/**
 * Factory for creating [EnvironmentOrchestrator] instances with all required dependencies.
 *
 * This factory reduces duplication by centralizing the creation of all repositories,
 * API factories, and the orchestrator itself. It ensures all components are properly
 * wired together with consistent configuration.
 *
 * Example usage:
 * ```
 * val factory = EnvironmentOrchestratorFactory(
 *     database = database,
 *     json = json,
 *     scope = coroutineScope,
 *     eventSerialBus = eventSerialBus,
 *     logger = logger
 * )
 * val orchestrator = factory.create()
 * ```
 */
class EnvironmentOrchestratorFactory(
    private val database: Database,
    private val json: Json,
    private val scope: CoroutineScope,
    private val eventSerialBus: EventSerialBus,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /**
     * Create an [EnvironmentOrchestrator] with all dependencies initialized.
     *
     * This creates:
     * - All domain repositories (meeting, ticket, message, event)
     * - All API factories (meeting, message, event)
     * - The orchestrator itself with all dependencies wired together
     */
    fun create(): EnvironmentOrchestrator {
        // Create repositories
        val meetingRepository = MeetingRepository(
            json = json,
            scope = scope,
            database = database,
        )

        val ticketRepository = TicketRepository(
            database = database,
        )

        val messageRepository = MessageRepository(
            json = json,
            scope = scope,
            database = database,
        )

        val eventRepository = EventRepository(
            json = json,
            scope = scope,
            database = database,
        )

        // Create a temporary meeting orchestrator for the factory
        // Note: The actual meeting orchestrator will be created by EnvironmentOrchestrator
        val tempMeetingOrchestrator = createTemporaryMeetingOrchestrator(
            meetingRepository = meetingRepository,
            messageRepository = messageRepository,
        )

        // Create API factories
        val meetingApiFactory = AgentMeetingsApiFactory(
            meetingOrchestrator = tempMeetingOrchestrator,
            logger = logger,
        )

        val messageApiFactory = AgentMessageApiFactory(
            messageRepository = messageRepository,
            eventSerialBus = eventSerialBus,
        )

        val eventApiFactory = AgentEventApiFactory(
            eventRepository = eventRepository,
            eventSerialBus = eventSerialBus,
            logger = logger,
        )

        // Create the environment orchestrator
        return EnvironmentOrchestrator(
            meetingRepository = meetingRepository,
            meetingApiFactory = meetingApiFactory,
            ticketRepository = ticketRepository,
            messageRepository = messageRepository,
            messageApiFactory = messageApiFactory,
            eventRepository = eventRepository,
            eventApiFactory = eventApiFactory,
            eventSerialBus = eventSerialBus,
            logger = logger,
        )
    }

    /**
     * Creates a temporary MeetingOrchestrator for the MeetingApiFactory.
     *
     * Note: This is a workaround for the circular dependency between MeetingOrchestrator
     * and AgentMeetingsApiFactory. The final MeetingOrchestrator will be created by
     * the EnvironmentOrchestrator itself.
     */
    private fun createTemporaryMeetingOrchestrator(
        meetingRepository: MeetingRepository,
        messageRepository: MessageRepository,
    ): MeetingOrchestrator {
        // Create a temporary message API for the orchestrator
        val tempMessageApi = AgentMessageApi(
            agentId = "TEMP_FACTORY_AGENT",
            messageRepository = messageRepository,
            eventSerialBus = eventSerialBus,
            logger = logger,
        )

        return MeetingOrchestrator(
            repository = meetingRepository,
            eventSerialBus = eventSerialBus,
            messageApi = tempMessageApi,
            logger = logger,
        )
    }
}
