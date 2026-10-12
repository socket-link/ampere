package link.socket.ampere.commitment

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.escalation.Escalation
import link.socket.ampere.agents.events.meetings.MeetingSchedulingService
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.internal.DefaultTicketService
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject

/**
 * Commitments come from assignment (AMPR-449, F31).
 *
 * Both publishers of the ticket events — [TicketOrchestrator] and [TicketActionService], the
 * latter through the SDK's [DefaultTicketService] — run on one in-memory database and fold into
 * one [CommitmentLedger]. Every scenario runs once per publisher, so a commitment that only one
 * path forms or resolves fails here. Bodies use `runBlocking`: the door persists on a real IO
 * dispatcher.
 */
class CommitmentLedgerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var ticketRepository: TicketRepository
    private lateinit var store: CommitmentStore
    private lateinit var ledger: CommitmentLedger
    private lateinit var ticketService: DefaultTicketService
    private lateinit var orchestrator: TicketOrchestrator

    private val due: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()) + 3.days

    /** One publisher of `TicketAssigned` and `TicketStatusChanged`. */
    private interface Publisher {
        val name: String
        suspend fun assign(ticketId: TicketId, agentId: AgentId?)
        suspend fun transition(ticketId: TicketId, status: TicketStatus)
    }

    private val actionServicePath = object : Publisher {
        override val name = "TicketActionService"

        override suspend fun assign(ticketId: TicketId, agentId: AgentId?) {
            ticketService.assign(ticketId, agentId).getOrThrow()
        }

        override suspend fun transition(ticketId: TicketId, status: TicketStatus) {
            ticketService.transition(ticketId, status).getOrThrow()
        }
    }

    private val orchestratorPath = object : Publisher {
        override val name = "TicketOrchestrator"

        // The orchestrator lets only the creator or the assignee act on a ticket.
        override suspend fun assign(ticketId: TicketId, agentId: AgentId?) {
            orchestrator.assignTicket(ticketId, agentId, assignerAgentId = actor(ticketId)).getOrThrow()
        }

        override suspend fun transition(ticketId: TicketId, status: TicketStatus) {
            orchestrator.transitionTicketStatus(ticketId, status, actorAgentId = actor(ticketId)).getOrThrow()
        }

        private suspend fun actor(ticketId: TicketId): AgentId =
            assertNotNull(ticketRepository.getTicket(ticketId).getOrThrow()).createdByAgentId
    }

    private val publishers: List<Publisher> = listOf(actionServicePath, orchestratorPath)

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "ticket-orchestrator", scope = scope)
        ticketRepository = TicketRepository(handle.database)
        store = SqlDelightCommitmentStore(handle.database)
        ledger = CommitmentLedger(store)

        ticketService = DefaultTicketService(
            actionService = TicketActionService(ticketRepository, handle.api, ledger),
            viewService = DefaultTicketViewService(ticketRepository),
            ticketRepository = ticketRepository,
        )
        orchestrator = TicketOrchestrator(
            ticketRepository = ticketRepository,
            eventApi = handle.api,
            messageApi = AgentMessageApi(
                "ticket-orchestrator",
                MessageRepository(DEFAULT_JSON, scope, handle.database),
                handle.api,
            ),
            meetingSchedulingService = MeetingSchedulingService { _, _ -> error("no meetings in this test") },
            commitmentLedger = ledger,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    private var created = 0

    /**
     * A ticket created through the SDK, with [dueAt] set through its builder. Titled uniquely: the
     * orchestrator finds a ticket's thread by the title in its first message, and a blocked
     * ticket's thread refuses posts until a human answers.
     */
    private suspend fun ticket(dueAt: Instant?): TicketId =
        ticketService.create("Ticket #${++created}: ship login") { dueDate(dueAt) }.getOrThrow().id

    private suspend fun commitments(ticketId: TicketId): List<Commitment> = store.forTicket(ticketId).getOrThrow()

    private suspend fun recorded(eventId: String): StoredEvent =
        assertNotNull(handle.repository.getStoredEventById(eventId).getOrThrow(), "event $eventId was recorded")

    @Test
    fun `the SDK creates a ticket with the due date it was given`() = runBlocking<Unit> {
        val ticketId = ticket(due)

        assertEquals(due, ticketService.get(ticketId).getOrThrow().dueDate)
        assertNull(ticketService.get(ticket(dueAt = null)).getOrThrow().dueDate)
    }

    @Test
    fun `assigning a ticket with a due date forms an open commitment`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(due)

            publisher.assign(ticketId, AGENT_A)

            val commitment = commitments(ticketId).single()
            assertEquals(Ref<Subject>(AGENT_A), commitment.committer, publisher.name)
            assertEquals(ticketId, commitment.ticketId, publisher.name)
            assertEquals(due, commitment.due, publisher.name)
            assertEquals(Commitment.Status.OPEN, commitment.status, publisher.name)
            assertNull(commitment.goal, publisher.name)
            assertNull(commitment.resolvedBy, publisher.name)

            // The row is the fold of the event: its provenance is the TicketAssigned as recorded.
            val forming = recorded(commitment.provenance.createdBy)
            val assigned = assertIs<TicketEvent.TicketAssigned>(forming.event, publisher.name)
            assertEquals(ticketId, assigned.ticketId, publisher.name)
            assertEquals(AGENT_A, assigned.assignedTo, publisher.name)
            assertEquals(forming.recordedAt, commitment.provenance.createdAt, publisher.name)
        }
    }

    @Test
    fun `assigning a ticket without a due date forms nothing`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(dueAt = null)

            publisher.assign(ticketId, AGENT_A)

            assertTrue(commitments(ticketId).isEmpty(), publisher.name)
        }
    }

    @Test
    fun `reassigning resolves the old holder's commitment and forms the new one`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(due)
            publisher.assign(ticketId, AGENT_A)

            publisher.assign(ticketId, AGENT_B)

            // Keyed by holder: two events in one millisecond share a created_at.
            val byHolder = commitments(ticketId).associateBy { it.committer.value }
            val old = assertNotNull(byHolder[AGENT_A], publisher.name)
            val new = assertNotNull(byHolder[AGENT_B], publisher.name)
            assertEquals(2, byHolder.size, publisher.name)
            assertEquals(Commitment.Status.RESOLVED, old.status, publisher.name)
            assertEquals(Commitment.Status.OPEN, new.status, publisher.name)
            // One event both resolved A's and formed B's.
            assertEquals(new.provenance.createdBy, old.resolvedBy, publisher.name)
            assertEquals(new.provenance.createdAt, old.resolvedAt, publisher.name)
        }
    }

    @Test
    fun `moving the ticket to done resolves the commitment`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(due)
            publisher.assign(ticketId, AGENT_A)

            publisher.transition(ticketId, TicketStatus.Done)

            val commitment = commitments(ticketId).single()
            assertEquals(Commitment.Status.RESOLVED, commitment.status, publisher.name)
            val resolving = recorded(assertNotNull(commitment.resolvedBy, publisher.name))
            val changed = assertIs<TicketEvent.TicketStatusChanged>(resolving.event, publisher.name)
            assertEquals(TicketStatus.Done, changed.newStatus, publisher.name)
            assertEquals(resolving.recordedAt, commitment.resolvedAt, publisher.name)
        }
    }

    @Test
    fun `a move to any status but done leaves the commitment open`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(due)
            publisher.assign(ticketId, AGENT_A)

            publisher.transition(ticketId, TicketStatus.Ready)

            assertEquals(Commitment.Status.OPEN, commitments(ticketId).single().status, publisher.name)
        }
    }

    @Test
    fun `unassigning resolves the commitment and forms none`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(due)
            publisher.assign(ticketId, AGENT_A)

            publisher.assign(ticketId, null)

            assertEquals(Commitment.Status.RESOLVED, commitments(ticketId).single().status, publisher.name)
        }
    }

    @Test
    fun `reassigning to the same holder keeps the commitment it has`() = runBlocking<Unit> {
        for (publisher in publishers) {
            val ticketId = ticket(due)
            publisher.assign(ticketId, AGENT_A)
            val first = commitments(ticketId).single()

            publisher.assign(ticketId, AGENT_A)

            assertEquals(listOf(first), commitments(ticketId), publisher.name)
        }
    }

    @Test
    fun `a commitment one publisher formed the other resolves`() = runBlocking<Unit> {
        val viaOrchestrator = ticket(due)
        orchestratorPath.assign(viaOrchestrator, AGENT_A)
        actionServicePath.transition(viaOrchestrator, TicketStatus.Done)

        val viaActionService = ticket(due)
        actionServicePath.assign(viaActionService, AGENT_A)
        orchestratorPath.assign(viaActionService, AGENT_B)

        assertEquals(Commitment.Status.RESOLVED, commitments(viaOrchestrator).single().status)
        assertEquals(
            mapOf(AGENT_A to Commitment.Status.RESOLVED, AGENT_B to Commitment.Status.OPEN),
            commitments(viaActionService).associate { it.committer.value to it.status },
        )
    }

    /** Assigns [ticketId] to [holder] and works it up to In Progress, where it can be blocked. */
    private suspend fun inProgress(ticketId: TicketId, holder: AgentId) {
        orchestratorPath.assign(ticketId, holder)
        orchestratorPath.transition(ticketId, TicketStatus.Ready)
        orchestratorPath.transition(ticketId, TicketStatus.InProgress)
    }

    /** Blocks [ticketId] for an approval (no meeting), handing it to [handTo] when given. */
    private suspend fun block(ticketId: TicketId, handTo: AgentId?) {
        val reporter = assertNotNull(ticketRepository.getTicket(ticketId).getOrThrow()).createdByAgentId
        orchestrator.blockTicket(
            ticketId = ticketId,
            blockingReason = "Needs a license",
            escalationType = Escalation.Decision.Authorization,
            reportedByAgentId = reporter,
            assignedToAgentId = handTo,
        ).getOrThrow()
    }

    @Test
    fun `a blocker that reassigns the ticket hands the commitment to the new holder`() = runBlocking<Unit> {
        val ticketId = ticket(due)
        inProgress(ticketId, AGENT_A)

        block(ticketId, handTo = AGENT_B)

        val byHolder = commitments(ticketId).associateBy { it.committer.value }
        val old = assertNotNull(byHolder[AGENT_A])
        val new = assertNotNull(byHolder[AGENT_B])
        assertEquals(Commitment.Status.RESOLVED, old.status)
        assertEquals(Commitment.Status.OPEN, new.status)
        assertEquals(new.provenance.createdBy, old.resolvedBy)

        // The handover is a TicketAssigned of its own, caused by the TicketBlocked.
        val handover = recorded(new.provenance.createdBy)
        assertEquals(AGENT_B, assertIs<TicketEvent.TicketAssigned>(handover.event).assignedTo)
        val cause = recorded(assertNotNull(handover.causedBy))
        assertEquals(ticketId, assertIs<TicketEvent.TicketBlocked>(cause.event).ticketId)
    }

    @Test
    fun `a blocker that keeps the holder announces no reassignment`() = runBlocking<Unit> {
        for (handTo in listOf(null, AGENT_A)) {
            val ticketId = ticket(due)
            inProgress(ticketId, AGENT_A)

            block(ticketId, handTo)

            assertEquals(Commitment.Status.OPEN, commitments(ticketId).single().status, "handTo=$handTo")
            val assignments = handle.repository.getEventsByType(TicketEvent.TicketAssigned.EVENT_TYPE).getOrThrow()
                .filterIsInstance<TicketEvent.TicketAssigned>()
                .filter { it.ticketId == ticketId }
            assertEquals(1, assignments.size, "handTo=$handTo")
        }
    }

    @Test
    fun `folding the same events twice changes nothing`() = runBlocking<Unit> {
        val ticketId = ticket(due)
        actionServicePath.assign(ticketId, AGENT_A)
        val formed = commitments(ticketId).single()
        val event = assertIs<TicketEvent.TicketAssigned>(recorded(formed.provenance.createdBy).event)
        val ticket = assertNotNull(ticketRepository.getTicket(ticketId).getOrThrow())

        assertEquals(formed, ledger.assigned(event, formed.provenance.createdAt, ticket).getOrThrow())
        assertEquals(listOf(formed), commitments(ticketId))

        actionServicePath.transition(ticketId, TicketStatus.Done)
        val resolved = commitments(ticketId).single()
        val done = assertIs<TicketEvent.TicketStatusChanged>(recorded(assertNotNull(resolved.resolvedBy)).event)

        assertTrue(ledger.statusChanged(done, Clock.System.now()).getOrThrow().isEmpty())
        assertEquals(listOf(resolved), commitments(ticketId))
    }

    @Test
    fun `the open commitments are listed soonest due first`() = runBlocking<Unit> {
        val later = ticket(due + 1.days)
        val sooner = ticket(due)
        actionServicePath.assign(later, AGENT_A)
        orchestratorPath.assign(sooner, AGENT_B)

        assertEquals(
            listOf(sooner, later),
            store.withStatus(Commitment.Status.OPEN).getOrThrow().map { it.ticketId },
        )
    }

    private companion object {
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
    }
}
