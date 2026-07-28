package link.socket.ampere.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.internal.DefaultAgentService
import link.socket.ampere.api.internal.DefaultEventService
import link.socket.ampere.api.internal.DefaultKnowledgeService
import link.socket.ampere.api.internal.DefaultOutcomeService
import link.socket.ampere.api.internal.DefaultPricingService
import link.socket.ampere.api.internal.DefaultStatusService
import link.socket.ampere.api.internal.DefaultThreadService
import link.socket.ampere.api.internal.DefaultTicketService
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.memory.MemoryStore

/**
 * Create an [AmpereInstance] backed by existing infrastructure.
 *
 * Used by external consumers (CLI, Socket client) to share database, scope,
 * and event bus between their own orchestration layer and the public API
 * surface. The returned instance does not own the lifecycle of the
 * underlying resources — the caller is responsible for cleanup.
 *
 * This is the **light** construction path Socket consumes: the caller owns
 * the database driver, coroutine scope, and event bus; Ampere just composes
 * services on top. The **heavy** path ([Ampere.create]) is JVM-only and
 * constructs its own infrastructure.
 *
 * @param environmentService The shared environment providing repositories and event bus
 * @param knowledgeRepository The shared knowledge repository
 * @param workspace Optional workspace path for status reporting
 * @param memoryStore Optional [MemoryStore] override. When supplied,
 *   [MemoryStore.knowledge] takes precedence over [knowledgeRepository] and
 *   [MemoryStore.outcomes] takes precedence over
 *   [EnvironmentService.outcomeMemoryRepository]. Embedded consumers (e.g.
 *   Socket) pass this to route durable agent memory through their own
 *   on-device store while reusing Ampere's other infrastructure.
 * @param upstreamLlmClient Transport for outbound LLM calls. Wired into the
 *   returned [AmpereInstance.agentFactory], so every agent built off this
 *   instance routes its LLM calls through it without per-construction-site
 *   plumbing, and exposed on [AmpereInstance.upstreamLlmClient] for callers
 *   that construct agents by hand (e.g.
 *   [link.socket.ampere.agents.definition.SparkBasedAgent.Code]). Omitting it
 *   leaves agents without a transport: their first LLM call throws
 *   [MissingUpstreamLlmClientException][link.socket.ampere.llm.MissingUpstreamLlmClientException]
 *   rather than calling a provider directly (AMPR-236). Pass
 *   [BundledUpstreamLlmClient] to opt into the direct per-provider call.
 * @param agentScope Coroutine scope the instance's [AmpereInstance.agentFactory]
 *   hands to the agents it builds. Defaults to a fresh `Dispatchers.Default`
 *   scope; pass the environment's own scope to share cancellation.
 */
@AmpereStableApi
fun Ampere.fromEnvironment(
    environmentService: EnvironmentService,
    knowledgeRepository: KnowledgeRepository,
    workspace: String? = null,
    memoryStore: MemoryStore? = null,
    upstreamLlmClient: UpstreamLlmClient? = null,
    agentScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
): AmpereInstance {
    val sdkEventApi = environmentService.createEventApi("sdk-cli")

    val effectiveKnowledgeRepository = memoryStore?.knowledge ?: knowledgeRepository
    val effectiveOutcomeRepository = memoryStore?.outcomes ?: environmentService.outcomeMemoryRepository

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
        outcomeRepository = effectiveOutcomeRepository,
    )

    val pricingService = DefaultPricingService()

    val knowledgeService = DefaultKnowledgeService(
        knowledgeRepository = effectiveKnowledgeRepository,
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

    // The seam that makes the injected transport actually govern agent LLM
    // calls (AMPR-236): agents built off this instance inherit the client
    // instead of falling through to the direct-provider default.
    val boundAgentFactory = AgentFactory(
        scope = agentScope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        eventApiFactory = { agentId -> environmentService.createEventApi(agentId) },
        eventSerialBus = environmentService.eventBus,
        upstreamLlmClient = upstreamLlmClient,
    )

    return object : AmpereInstance {
        override val agents = agentService
        override val tickets = ticketService
        override val threads = threadService
        override val events = eventService
        override val outcomes = outcomeService
        override val pricing = pricingService
        override val knowledge = knowledgeService
        override val status = statusService
        override val upstreamLlmClient: UpstreamLlmClient? = upstreamLlmClient
        override val agentFactory: AgentFactory = boundAgentFactory
        override fun close() {
            // No-op: caller owns the lifecycle of shared resources
        }
    }
}
