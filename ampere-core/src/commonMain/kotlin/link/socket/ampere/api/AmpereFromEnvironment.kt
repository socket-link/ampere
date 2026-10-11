package link.socket.ampere.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorSource
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.tools.Tool
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
import link.socket.ampere.db.Database
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.propel.RunHost
import link.socket.ampere.propel.rosterRunHost

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
 * @param workspace The directory agents built off this instance's
 *   [AmpereInstance.agentFactory] are confined to (AMPR-300), also reported by
 *   [AmpereInstance.status]. Required and explicit: there is no default
 *   workspace, so a caller that composes an instance must say where its agents
 *   may write.
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
 * @param upstreamDecisionClient Transport for outbound decision calls (AMPR-384),
 *   the sibling of [upstreamLlmClient] for the Decide call kind. Wired into the
 *   returned [AmpereInstance.agentFactory] and exposed on
 *   [AmpereInstance.upstreamDecisionClient]. Omitting it leaves agents without
 *   one: their first `decide` throws
 *   [MissingUpstreamDecisionClientException][link.socket.ampere.llm.decide.MissingUpstreamDecisionClientException];
 *   nothing falls back to the model-backed adapter.
 * @param agentScope Coroutine scope the instance's [AmpereInstance.agentFactory]
 *   hands to the agents it builds, and the one a hosted run's seats publish their
 *   spark events and cognitive snapshots on. **The caller's to own**: nothing here
 *   cancels it, and a scope that outlives the database those publishes write to is
 *   how a closed driver gets written to. Defaults to a fresh `Dispatchers.Default`
 *   scope; pass the environment's own to share cancellation. It is not the scope a
 *   run executes on — that is whichever scope calls
 *   [RunHost.open][link.socket.ampere.propel.RunHost.open].
 * @param modelDescriptorSource Catalog source for the default relay's model
 *   registry (AMPR-231). Null keeps the bundled cloud catalog. Has no effect
 *   if a caller constructs agents with their own [AgentFactory] supplying a
 *   [link.socket.ampere.agents.domain.routing.CognitiveRelay] directly.
 * @param database Backing store for a persisted
 *   [link.socket.ampere.plug.permission.UserGrantStore] (AMPR-348). When
 *   supplied, [boundAgentFactory] gates plug-tool dispatch against the
 *   caller's real grants instead of the deny-all default every
 *   `requiredPermissions` tool otherwise falls back to. Null preserves that
 *   default, which stays correct for callers with no persisted store.
 * @param executor What the agents built off [AmpereInstance.agentFactory] dispatch
 *   their tool calls through (AMPR-405). Defaults to [FunctionExecutor.create], so a
 *   composed instance's agents can actually run the in-process tools they are built
 *   with — before AMPR-405 this path supplied no executor, so the bound factory's
 *   agents built no `ToolExecutionEngine` and every plan step naming a tool failed.
 *   Pass `null` to declare that these agents must not act.
 * @param tools Extra tools layered onto every agent the bound factory builds
 *   (AMPR-405), on top of each agent type's own set. Narrowed by the spark stack
 *   like any other tool. A hosted run's tools are the ones passed to
 *   [RunHost.open][link.socket.ampere.propel.RunHost.open] instead: a run declares
 *   what it may dispatch, per run, rather than inheriting an instance-wide set.
 * @param cognitiveRelay Capability- and cost-aware routing for the agents this
 *   instance builds (AMPR-219, row H9). Reaches the bound [AgentFactory], so a
 *   factory-built agent routes through the relay rather than using its static
 *   [link.socket.ampere.domain.ai.configuration.AIConfiguration] directly. Null keeps
 *   the factory's own default relay. A hosted run's seats declare their own on
 *   [HostedAgent][link.socket.ampere.propel.HostedAgent], because a roster is where
 *   per-seat routing belongs.
 */
@AmpereStableApi
fun Ampere.fromEnvironment(
    environmentService: EnvironmentService,
    knowledgeRepository: KnowledgeRepository,
    workspace: String,
    memoryStore: MemoryStore? = null,
    upstreamLlmClient: UpstreamLlmClient? = null,
    agentScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    modelDescriptorSource: ModelDescriptorSource? = null,
    database: Database? = null,
    upstreamDecisionClient: UpstreamDecisionClient? = null,
    executor: Executor? = FunctionExecutor.create(),
    tools: Set<Tool<*>> = emptySet(),
    cognitiveRelay: CognitiveRelay? = null,
): AmpereInstance {
    val sdkEventApi = environmentService.createEventApi("sdk-cli")

    val effectiveKnowledgeRepository = memoryStore?.knowledge ?: knowledgeRepository
    val effectiveOutcomeRepository = memoryStore?.outcomes ?: environmentService.outcomeMemoryRepository

    // AMPR-393: the hosted run. Its seats get their own doors off the same environment,
    // so a seat's events are attributed to the seat and not to the SDK's own door, and
    // they dispatch through the same executor the bound factory's agents do.
    val runHost: RunHost = rosterRunHost(
        createEventApi = environmentService::createEventApi,
        agentScope = agentScope,
        executor = executor,
    )

    val agentService = DefaultAgentService(
        agentActionService = AgentActionService(eventApi = sdkEventApi),
        runHost = runHost,
        upstreamLlmClient = upstreamLlmClient,
        cognitiveRelay = cognitiveRelay,
        // Not `memoryStore` — the caller's override when there is one, else the same two
        // repositories this instance's own `knowledge` and `outcomes` services read. A
        // seat with nowhere to write recalls nothing and stores nothing, and a run that
        // produced outcomes and no Knowledge has not closed its loop; this path has both
        // repositories in hand, so there is no reason for a run opened off it to be in
        // that state because the caller did not pass a store it never needed.
        memoryStore = memoryStore ?: memoryStoreOf(
            knowledge = effectiveKnowledgeRepository,
            outcomes = effectiveOutcomeRepository,
        ),
        tools = tools,
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
        workspace = ExecutionWorkspace(baseDirectory = workspace),
        createEventApi = environmentService::createEventApi,
        // AMPR-393 (row H9): the half of "fromEnvironment can host" that was missing.
        // `memoryStore` reached this instance's services and not the agents it builds,
        // so a factory-built agent recalled nothing and stored nothing — it had no
        // memory at all. It is the same repository `AmpereInstance.knowledge` reads.
        knowledgeRepository = effectiveKnowledgeRepository,
        cognitiveRelay = cognitiveRelay,
        upstreamLlmClient = upstreamLlmClient,
        upstreamDecisionClient = upstreamDecisionClient,
        modelDescriptorSource = modelDescriptorSource,
        database = database,
        // AMPR-405: the seam that makes a factory-built agent able to act at all. Without
        // an executor its reasoning unit builds no `ToolExecutionEngine`, so every plan
        // step naming a tool comes back "Tool execution engine not configured".
        executor = executor,
        additionalTools = tools,
        // AMPR-406: the same store [AmpereInstance.outcomes] reads, so what those agents'
        // tool calls do is what this instance reports back. It only records anything with
        // an executor beside it, which is why it is wired here and not before.
        outcomeRepository = effectiveOutcomeRepository,
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
        override val upstreamDecisionClient: UpstreamDecisionClient? = upstreamDecisionClient
        override val agentFactory: AgentFactory = boundAgentFactory
        override val runs: RunHost = runHost
        override fun close() {
            // No-op: caller owns the lifecycle of shared resources
        }
    }
}
