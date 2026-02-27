package link.socket.ampere.api

import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.internal.DefaultAgentService
import link.socket.ampere.api.internal.DefaultAmpereInstance
import link.socket.ampere.api.internal.DefaultEventService
import link.socket.ampere.api.internal.DefaultKnowledgeService
import link.socket.ampere.api.internal.DefaultOutcomeService
import link.socket.ampere.api.internal.DefaultStatusService
import link.socket.ampere.api.internal.DefaultThreadService
import link.socket.ampere.api.internal.DefaultTicketService

internal actual fun createInstance(config: AmpereConfig): AmpereInstance =
    DefaultAmpereInstance(config)

/**
 * Create an [AmpereInstance] backed by existing infrastructure.
 *
 * Used by the CLI to share database, scope, and event bus between
 * [link.socket.ampere.AmpereContext] (agent orchestration) and the
 * public API surface. The returned instance does not own the lifecycle
 * of the underlying resources — the caller is responsible for cleanup.
 *
 * @param environmentService The shared environment providing repositories and event bus
 * @param knowledgeRepository The shared knowledge repository
 * @param workspace Optional workspace path for status reporting
 */
fun Ampere.fromEnvironment(
    environmentService: EnvironmentService,
    knowledgeRepository: KnowledgeRepository,
    workspace: String? = null,
): AmpereInstance {
    val sdkEventApi = environmentService.createEventApi("sdk-cli")

    val agentService = DefaultAgentService(
        agentActionService = AgentActionService(eventApi = sdkEventApi),
        eventApi = sdkEventApi,
    )

    val ticketService = DefaultTicketService(
        actionService = TicketActionService(
            ticketRepository = environmentService.ticketRepository,
            eventApi = sdkEventApi,
        ),
        viewService = DefaultTicketViewService(
            ticketRepository = environmentService.ticketRepository,
        ),
        ticketRepository = environmentService.ticketRepository,
    )

    val threadService = DefaultThreadService(
        actionService = MessageActionService(
            messageRepository = environmentService.messageRepository,
            eventApi = sdkEventApi,
        ),
        viewService = DefaultThreadViewService(
            messageRepository = environmentService.messageRepository,
        ),
        eventRelayService = environmentService.eventRelayService,
    )

    val eventService = DefaultEventService(
        eventRelayService = environmentService.eventRelayService,
        eventRepository = environmentService.eventRepository,
    )

    val outcomeService = DefaultOutcomeService(
        outcomeRepository = environmentService.outcomeMemoryRepository,
    )

    val knowledgeService = DefaultKnowledgeService(
        knowledgeRepository = knowledgeRepository,
    )

    val statusService = DefaultStatusService(
        threadViewService = DefaultThreadViewService(
            messageRepository = environmentService.messageRepository,
        ),
        ticketViewService = DefaultTicketViewService(
            ticketRepository = environmentService.ticketRepository,
        ),
        ticketRepository = environmentService.ticketRepository,
        agentService = agentService,
        workspace = workspace,
    )

    return object : AmpereInstance {
        override val agents = agentService
        override val tickets = ticketService
        override val threads = threadService
        override val events = eventService
        override val outcomes = outcomeService
        override val knowledge = knowledgeService
        override val status = statusService
        override fun close() {
            // No-op: AmpereContext owns the lifecycle of shared resources
        }
    }
}
