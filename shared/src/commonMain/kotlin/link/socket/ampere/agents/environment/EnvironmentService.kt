package link.socket.ampere.agents.environment

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventClassType
import link.socket.ampere.agents.events.EventRegistry
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.meetings.AgentMeetingsApi
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.agents.events.relay.EventRelayServiceImpl
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

/**
 * Service layer that provides convenient access to the environment orchestrator and its components.
 *
 * EnvironmentService simplifies interaction with the EnvironmentOrchestrator by:
 * - Providing factory methods to create agent-specific APIs
 * - Exposing repositories for direct data access
 * - Providing access to orchestrators for lifecycle operations
 *
 * This is the recommended way for agents and handlers to interact with the environment,
 * rather than depending on EnvironmentOrchestrator directly.
 *
 * Usage:
 * ```kotlin
 * val service = EnvironmentService(orchestrator)
 * val messageApi = service.createMessageApi("my-agent")
 * val eventApi = service.createEventApi("my-agent")
 * ```
 */
class EnvironmentService(
    private val orchestrator: EnvironmentOrchestrator,
    val eventRelayService: EventRelayService,
) {
    /**
     * Access to the meeting repository for querying meeting data.
     */
    val meetingRepository: MeetingRepository
        get() = orchestrator.meetingRepository

    /**
     * Access to the ticket repository for querying ticket data.
     */
    val ticketRepository: TicketRepository
        get() = orchestrator.ticketRepository

    /**
     * Access to the message repository for querying message data.
     */
    val messageRepository: MessageRepository
        get() = orchestrator.messageRepository

    /**
     * Access to the event repository for querying event data.
     */
    val eventRepository: EventRepository
        get() = orchestrator.eventRepository

    /**
     * Create an [AgentMessageApi] for the given agent.
     *
     * The message API allows agents to:
     * - Send messages to channels and threads
     * - Query message history
     * - Escalate issues to humans
     *
     * @param agentId The ID of the agent
     * @return A new AgentMessageApi instance bound to this agent
     */
    fun createMessageApi(agentId: AgentId): AgentMessageApi =
        orchestrator.messageApiFactory.create(agentId)

    /**
     * Create an [AgentEventApi] for the given agent.
     *
     * The event API allows agents to:
     * - Publish events to the event bus
     * - Subscribe to event types
     * - Query event history
     *
     * @param agentId The ID of the agent
     * @return A new AgentEventApi instance bound to this agent
     */
    fun createEventApi(agentId: AgentId): AgentEventApi =
        orchestrator.eventApiFactory.create(agentId)

    /**
     * Create an [AgentMeetingsApi] for the given agent.
     *
     * The meetings API allows agents to:
     * - Schedule meetings
     * - Query meeting status
     * - Participate in meetings
     *
     * @param agentId The ID of the agent
     * @return A new AgentMeetingsApi instance bound to this agent
     */
    fun createMeetingsApi(agentId: AgentId): AgentMeetingsApi =
        orchestrator.meetingApiFactory.create(agentId)

    /**
     * Access to the event bus for subscribing to events.
     */
    val eventBus: EventSerialBus
        get() = orchestrator.eventSerialBus

    /**
     * Subscribe to events of a specific type.
     *
     * @param agentId The agent subscribing to the events
     * @param eventClassType The type of events to subscribe to
     * @param handler Handler to process events
     * @return Subscription that can be used to unsubscribe
     */
    fun subscribe(
        agentId: AgentId,
        eventClassType: EventClassType,
        handler: EventHandler<Event, Subscription>,
    ): Subscription =
        eventBus.subscribe(agentId, eventClassType, handler)

    /**
     * Subscribe to all events.
     *
     * Uses EventRegistry as the single source of truth for all event types.
     *
     * @param agentId The agent subscribing to the events
     * @param handler Handler to process events
     * @return List of subscriptions (one per event type)
     */
    fun subscribeToAll(
        agentId: AgentId,
        handler: EventHandler<Event, Subscription>,
    ): List<Subscription> {
        return EventRegistry.allEventTypes.map { eventType ->
            subscribe(agentId, eventType, handler)
        }
    }

    /**
     * Start all orchestrator services.
     *
     * This starts the event routing system. Should be called once during
     * application startup.
     */
    fun start() {
        orchestrator.start()
    }

    companion object {
        /**
         * Create an EnvironmentService with all dependencies from a database.
         *
         * This is a convenience method for CLI tools and tests that need to quickly
         * set up a complete environment.
         *
         * @param database The database to use
         * @param scope The coroutine scope for async operations
         * @param json JSON configuration (defaults to DEFAULT_JSON)
         * @param logger Event logger (defaults to ConsoleEventLogger)
         * @return A fully initialized EnvironmentService
         */
        fun create(
            database: Database,
            scope: CoroutineScope,
            json: Json = DEFAULT_JSON,
            logger: EventLogger = ConsoleEventLogger(),
        ): EnvironmentService {
            val eventSerialBus = EventSerialBus(scope, logger)
            val factory = EnvironmentOrchestratorFactory(
                database = database,
                json = json,
                scope = scope,
                eventSerialBus = eventSerialBus,
                logger = logger,
            )
            val orchestrator = factory.create()
            val eventRelayService = EventRelayServiceImpl(
                eventSerialBus = eventSerialBus,
                eventRepository = orchestrator.eventRepository,
            )
            return EnvironmentService(orchestrator, eventRelayService)
        }
    }
}
