package link.socket.ampere.api

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.model.HealthLevel
import link.socket.ampere.api.model.HealthStatus
import link.socket.ampere.api.model.OutcomeStats
import link.socket.ampere.api.model.SystemSnapshot
import link.socket.ampere.api.model.ThreadFilter
import link.socket.ampere.api.model.TicketFilter
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.api.service.EventService
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.api.service.OutcomeService
import link.socket.ampere.api.service.StatusService
import link.socket.ampere.api.service.ThreadService
import link.socket.ampere.api.service.TicketService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

/**
 * Consumer simulation test — validates that every method on every service
 * interface can be called with realistic arguments. This catches API
 * ergonomics issues, missing types, and signature problems at compile time.
 *
 * Uses dummy implementations (not real infrastructure) to ensure the
 * public API surface is consumable before any real wiring exists.
 */
class ConsumerSimulationTest {

    private val now = Clock.System.now()

    // ==================== Dummy Implementations ====================

    private val dummyTicket = Ticket(
        id = "ticket-1",
        title = "Fix auth",
        description = "Retry logic",
        priority = TicketPriority.HIGH,
        type = TicketType.BUG,
        status = TicketStatus.Backlog,
        assignedAgentId = null,
        createdAt = now,
        updatedAt = now,
    )

    private val stubTicketService = object : TicketService {
        override suspend fun create(title: String, configure: (link.socket.ampere.api.service.TicketBuilder.() -> Unit)?): Result<Ticket> {
            val builder = link.socket.ampere.api.service.TicketBuilder()
            configure?.invoke(builder)
            return Result.success(dummyTicket.copy(title = title, description = builder.description, priority = builder.priority, type = builder.type))
        }
        override suspend fun assign(ticketId: TicketId, agentId: AgentId?) = Result.success(Unit)
        override suspend fun transition(ticketId: TicketId, status: TicketStatus) = Result.success(Unit)
        override suspend fun get(ticketId: TicketId) = Result.success(dummyTicket)
        override suspend fun list(filter: TicketFilter?) = Result.success(listOf(
            TicketSummary(id = "ticket-1", title = "Fix auth", priority = TicketPriority.HIGH, status = TicketStatus.Backlog, assignedAgentId = null)
        ))
    }

    private val stubThreadService = object : ThreadService {
        override suspend fun create(title: String, configure: (link.socket.ampere.api.service.ThreadBuilder.() -> Unit)?): Result<MessageThread> {
            val builder = link.socket.ampere.api.service.ThreadBuilder()
            configure?.invoke(builder)
            return Result.success(MessageThread(id = "thread-1", title = title, createdAt = now))
        }
        override suspend fun post(threadId: MessageThreadId, content: String, senderId: String) =
            Result.success(Message(id = "msg-1", threadId = threadId, senderId = senderId, content = content, timestamp = now))
        override suspend fun get(threadId: MessageThreadId) =
            Result.success(ThreadDetail(id = threadId, title = "Thread", messages = emptyList(), createdAt = now))
        override suspend fun list(filter: ThreadFilter?) = Result.success(emptyList<ThreadSummary>())
        override fun observe(threadId: MessageThreadId): Flow<Message> = emptyFlow()
    }

    private val stubAgentService = object : AgentService {
        override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam = AgentTeam.create(configure)
        override suspend fun pursue(goal: String) = Result.success("goal-123")
        override suspend fun wake(agentId: AgentId) = Result.success(Unit)
        override suspend fun inspect(agentId: AgentId) = Result.success(
            AgentSnapshot(id = agentId, role = "engineer", state = AgentState.Active, currentTask = null, sparkStack = emptyList(), lastActivity = now)
        )
        override suspend fun listAll() = listOf(
            AgentSnapshot(id = "eng", role = "engineer", state = AgentState.Active, currentTask = "ticket-1", sparkStack = listOf("code-review"), lastActivity = now)
        )
        override suspend fun pause(agentId: AgentId) = Result.success(Unit)
    }

    private val stubEventService = object : EventService {
        override fun observe(filters: EventRelayFilters): Flow<Event> = emptyFlow()
        override suspend fun query(fromTime: Instant, toTime: Instant, sourceIds: Set<String>?) = Result.success(emptyList<Event>())
        override fun replay(from: Instant, to: Instant, filters: EventRelayFilters): Flow<Event> = emptyFlow()
    }

    private val stubOutcomeService = object : OutcomeService {
        override suspend fun forTicket(ticketId: TicketId) = Result.success(emptyList<OutcomeMemory>())
        override suspend fun search(query: String, limit: Int) = Result.success(emptyList<OutcomeMemory>())
        override suspend fun stats() = Result.success(OutcomeStats(totalOutcomes = 10, successCount = 8, failureCount = 2, successRate = 0.8, averageDurationMs = 1500))
        override suspend fun byExecutor(executorId: ExecutorId, limit: Int) = Result.success(emptyList<OutcomeMemory>())
    }

    private val stubKnowledgeService = object : KnowledgeService {
        override suspend fun store(knowledge: Knowledge, tags: List<String>, taskType: String?, complexityLevel: String?) =
            Result.success(KnowledgeEntry(id = "k-1", knowledgeType = KnowledgeType.OUTCOME, approach = "test", learnings = "test", timestamp = now))
        override suspend fun recall(query: String, limit: Int) = Result.success(emptyList<KnowledgeEntry>())
        override suspend fun provenance(knowledgeId: String) = Result.success(emptyList<KnowledgeEntry>())
    }

    private val stubStatusService = object : StatusService {
        override suspend fun snapshot() = Result.success(
            SystemSnapshot(agents = emptyList(), activeTickets = 5, totalTickets = 12, activeThreads = 3, totalMessages = 45, escalatedThreads = 1, workspace = "/project")
        )
        override fun health(): Flow<HealthStatus> = flowOf(
            HealthStatus(overall = HealthLevel.Healthy, activeAgents = 3, idleAgents = 1, pendingTickets = 2)
        )
    }

    // ==================== Consumer Simulation Tests ====================

    @Test
    fun `ticket lifecycle - create with DSL, assign, transition, list with filter`() = kotlinx.coroutines.runBlocking {
        // Create with builder DSL
        val ticket = stubTicketService.create("Fix auth retry") {
            description("Transient failures cause immediate failure")
            priority(TicketPriority.HIGH)
            type(TicketType.BUG)
        }.getOrThrow()

        assertNotNull(ticket)
        assertTrue(ticket.title == "Fix auth retry")

        // Assign
        stubTicketService.assign(ticket.id, "engineer-agent").getOrThrow()

        // Transition
        stubTicketService.transition(ticket.id, TicketStatus.InProgress).getOrThrow()

        // Get
        val retrieved = stubTicketService.get(ticket.id).getOrThrow()
        assertNotNull(retrieved)

        // List with filter
        val filtered = stubTicketService.list(TicketFilter(
            priority = TicketPriority.HIGH,
            type = TicketType.BUG,
        )).getOrThrow()
        assertTrue(filtered.isNotEmpty())

        // List without filter
        val all = stubTicketService.list().getOrThrow()
        assertNotNull(all)
    }

    @Test
    fun `thread lifecycle - create with DSL, post, observe, list with filter`() = kotlinx.coroutines.runBlocking {
        // Create with builder DSL
        val thread = stubThreadService.create("Auth design discussion") {
            participants("pm-agent", "engineer-agent")
        }.getOrThrow()

        assertNotNull(thread)

        // Post messages
        val msg = stubThreadService.post(thread.id, "What about OAuth2 PKCE?").getOrThrow()
        assertNotNull(msg)

        // Post as specific sender
        stubThreadService.post(thread.id, "Approved.", senderId = "pm-agent").getOrThrow()

        // Get thread detail
        val detail = stubThreadService.get(thread.id).getOrThrow()
        assertNotNull(detail)

        // Observe thread
        val messageFlow: Flow<Message> = stubThreadService.observe(thread.id)
        assertNotNull(messageFlow)

        // List with filter
        stubThreadService.list(ThreadFilter(hasEscalations = true)).getOrThrow()

        // List without filter
        stubThreadService.list().getOrThrow()
    }

    @Test
    fun `agent lifecycle - team DSL, pursue, inspect, listAll, pause`() = kotlinx.coroutines.runBlocking {
        // Pursue a goal
        val goalId = stubAgentService.pursue("Build authentication system").getOrThrow()
        assertTrue(goalId.startsWith("goal-"))

        // Wake an agent
        stubAgentService.wake("reviewer-agent").getOrThrow()

        // Inspect an agent
        val snapshot = stubAgentService.inspect("engineer-agent").getOrThrow()
        assertNotNull(snapshot)
        assertTrue(snapshot.state == AgentState.Active)

        // List all agents
        val agents = stubAgentService.listAll()
        assertTrue(agents.isNotEmpty())
        assertTrue(agents.first().sparkStack.isNotEmpty())

        // Pause an agent
        stubAgentService.pause("engineer-agent").getOrThrow()
    }

    @Test
    fun `event service - observe with filters, query time range, replay`() = kotlinx.coroutines.runBlocking {
        // Observe all events
        val allEvents: Flow<Event> = stubEventService.observe()
        assertNotNull(allEvents)

        // Observe with filters
        val filtered: Flow<Event> = stubEventService.observe(EventRelayFilters())
        assertNotNull(filtered)

        // Query time range
        val from = now - kotlin.time.Duration.parse("1h")
        val to = now
        val events = stubEventService.query(from, to).getOrThrow()
        assertNotNull(events)

        // Query with source filter
        stubEventService.query(from, to, sourceIds = setOf("pm-agent")).getOrThrow()

        // Replay
        val replay: Flow<Event> = stubEventService.replay(from, to)
        assertNotNull(replay)
    }

    @Test
    fun `outcome service - search, stats, byTicket, byExecutor`() = kotlinx.coroutines.runBlocking {
        // Search
        val results = stubOutcomeService.search("authentication retry", limit = 10).getOrThrow()
        assertNotNull(results)

        // Stats
        val stats = stubOutcomeService.stats().getOrThrow()
        assertTrue(stats.successRate > 0)
        assertTrue(stats.totalOutcomes == stats.successCount + stats.failureCount)

        // By ticket
        stubOutcomeService.forTicket("ticket-123").getOrThrow()

        // By executor
        stubOutcomeService.byExecutor("engineer-agent", limit = 5).getOrThrow()
    }

    @Test
    fun `knowledge service - recall, provenance`() = kotlinx.coroutines.runBlocking {
        // Recall
        val entries = stubKnowledgeService.recall("how did we handle auth?").getOrThrow()
        assertNotNull(entries)

        // Recall with limit
        stubKnowledgeService.recall("retry patterns", limit = 10).getOrThrow()

        // Provenance
        val trail = stubKnowledgeService.provenance("knowledge-456").getOrThrow()
        assertNotNull(trail)
    }

    @Test
    fun `status service - snapshot and health flow`() = kotlinx.coroutines.runBlocking {
        // Snapshot
        val snapshot = stubStatusService.snapshot().getOrThrow()
        assertTrue(snapshot.activeTickets > 0)
        assertNotNull(snapshot.workspace)

        // Health flow
        val healthFlow: Flow<HealthStatus> = stubStatusService.health()
        assertNotNull(healthFlow)
    }

    @Test
    fun `AmpereInstance interface provides all service accessors`() {
        // Verify the AmpereInstance interface shape by creating a stub
        val instance = object : AmpereInstance {
            override val agents: AgentService = stubAgentService
            override val tickets: TicketService = stubTicketService
            override val threads: ThreadService = stubThreadService
            override val events: EventService = stubEventService
            override val outcomes: OutcomeService = stubOutcomeService
            override val knowledge: KnowledgeService = stubKnowledgeService
            override val status: StatusService = stubStatusService
            override fun close() {}
        }

        assertNotNull(instance.agents)
        assertNotNull(instance.tickets)
        assertNotNull(instance.threads)
        assertNotNull(instance.events)
        assertNotNull(instance.outcomes)
        assertNotNull(instance.knowledge)
        assertNotNull(instance.status)
    }
}
