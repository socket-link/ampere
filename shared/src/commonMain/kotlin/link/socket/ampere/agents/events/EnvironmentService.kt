package link.socket.ampere.agents.events

import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.meetings.AgentMeetingsApi
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.tickets.TicketRepository

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
     * Start all orchestrator services.
     *
     * This starts the event routing system. Should be called once during
     * application startup.
     */
    fun start() {
        orchestrator.start()
    }
}
