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
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.CommitmentEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.subject.SubjectRegistry

/**
 * Undertakings that are not tickets (AMPR-475, F36).
 *
 * A consumer announces a planned work session through its door, then folds the recorded event
 * into a ledger it built with [CommitmentLedger.over] — the path a consumer with no environment
 * takes. Bodies use `runBlocking`: the door persists on a real IO dispatcher.
 */
class CommitmentLedgerUndertakingTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var store: CommitmentStore
    private lateinit var ledger: CommitmentLedger

    private val sessionEnd: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()) + 2.hours

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "planner", scope = scope)
        store = SqlDelightCommitmentStore(handle.database)
        ledger = CommitmentLedger.over(handle.database)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    private var events = 0

    private fun undertaking(
        workId: String = WORK_ID,
        due: Instant = sessionEnd,
        committer: String = PRINCIPAL,
    ) = CommitmentEvent.Undertaken(
        eventId = "undertaken-${++events}",
        committer = committer,
        workId = workId,
        due = due,
        description = "A work session the person planned",
        eventSource = EventSource.Human,
        timestamp = Clock.System.now(),
    )

    private fun release(workId: String = WORK_ID) = CommitmentEvent.Released(
        eventId = "released-${++events}",
        workId = workId,
        reason = "The session is over",
        eventSource = EventSource.Human,
        timestamp = Clock.System.now(),
    )

    /** Publish through the door first, then fold with the `recorded_at` it returned. */
    private suspend fun undertake(event: CommitmentEvent.Undertaken): Commitment {
        val recorded = handle.api.publish(event).getOrThrow()
        return ledger.undertaken(event, recorded.recordedAt).getOrThrow()
    }

    private suspend fun releaseAll(event: CommitmentEvent.Released): List<Commitment> {
        val recorded = handle.api.publish(event).getOrThrow()
        return ledger.released(event, recorded.recordedAt).getOrThrow()
    }

    private suspend fun commitments(workId: String = WORK_ID): List<Commitment> = store.forTicket(workId).getOrThrow()

    private suspend fun recorded(eventId: String): StoredEvent =
        assertNotNull(handle.repository.getStoredEventById(eventId).getOrThrow(), "event $eventId was recorded")

    @Test
    fun `an undertaking forms the Principal's open commitment on the work`() = runBlocking<Unit> {
        val event = undertaking()

        val formed = undertake(event)

        assertEquals(listOf(formed), commitments())
        assertEquals(SubjectRegistry.PRINCIPAL_REF, formed.committer)
        assertEquals(WORK_ID, formed.ticketId)
        assertEquals(sessionEnd, formed.due)
        assertEquals(Commitment.Status.OPEN, formed.status)
        assertNull(formed.goal)
        assertNull(formed.resolvedBy)

        // The row is the fold of the event: its provenance is the Undertaken as recorded.
        val forming = recorded(formed.provenance.createdBy)
        assertEquals(event, assertIs<CommitmentEvent.Undertaken>(forming.event))
        assertEquals(forming.recordedAt, formed.provenance.createdAt)
    }

    @Test
    fun `folding the same undertaking twice is one row`() = runBlocking<Unit> {
        val event = undertaking()
        val formed = undertake(event)

        assertEquals(formed, ledger.undertaken(event, Clock.System.now()).getOrThrow())
        assertEquals(listOf(formed), commitments())
    }

    @Test
    fun `a re-plan to a new due date resolves the old undertaking and forms the new one`() = runBlocking<Unit> {
        undertake(undertaking())
        val replan = undertaking(due = sessionEnd + 1.days)

        val new = undertake(replan)

        val byDue = commitments().associateBy { it.due }
        assertEquals(2, byDue.size)
        val old = assertNotNull(byDue[sessionEnd])
        assertEquals(Commitment.Status.RESOLVED, old.status)
        assertEquals(new, byDue[sessionEnd + 1.days])
        assertEquals(Commitment.Status.OPEN, new.status)
        // The second event both resolved the first undertaking and formed the new one.
        assertEquals(replan.eventId, old.resolvedBy)
        assertEquals(new.provenance.createdAt, old.resolvedAt)
    }

    @Test
    fun `re-folding an old undertaking after a re-plan leaves the re-plan open`() = runBlocking<Unit> {
        val first = undertaking()
        undertake(first)
        val new = undertake(undertaking(due = sessionEnd + 1.days))

        ledger.undertaken(first, Clock.System.now()).getOrThrow()

        assertEquals(new, commitments().single { it.status == Commitment.Status.OPEN })
    }

    @Test
    fun `a re-announcement at the same due date keeps the commitment it has`() = runBlocking<Unit> {
        val formed = undertake(undertaking())

        assertEquals(formed, undertake(undertaking()))
        assertEquals(listOf(formed), commitments())
    }

    @Test
    fun `an undertaking by someone else resolves the previous holder's`() = runBlocking<Unit> {
        undertake(undertaking())
        val handover = undertaking(committer = AGENT)

        undertake(handover)

        val byHolder = commitments().associateBy { it.committer.value }
        assertEquals(Commitment.Status.RESOLVED, assertNotNull(byHolder[PRINCIPAL]).status)
        assertEquals(handover.eventId, byHolder.getValue(PRINCIPAL).resolvedBy)
        assertEquals(Commitment.Status.OPEN, assertNotNull(byHolder[AGENT]).status)
    }

    @Test
    fun `a release resolves every open commitment on the work and nothing else`() = runBlocking<Unit> {
        val formed = undertake(undertaking())
        val other = undertake(undertaking(workId = OTHER_WORK_ID))
        val event = release()

        val released = releaseAll(event)

        val resolved = commitments().single()
        assertEquals(listOf(resolved), released)
        assertEquals(formed.provenance, resolved.provenance)
        assertEquals(Commitment.Status.RESOLVED, resolved.status)
        val resolving = recorded(assertNotNull(resolved.resolvedBy))
        assertEquals(event, assertIs<CommitmentEvent.Released>(resolving.event))
        assertEquals(resolving.recordedAt, resolved.resolvedAt)
        assertEquals(listOf(other), commitments(OTHER_WORK_ID))

        // Folding the release again resolves nothing more.
        assertTrue(ledger.released(event, Clock.System.now()).getOrThrow().isEmpty())
        assertEquals(listOf(resolved), commitments())
    }

    @Test
    fun `the Field lists open undertakings soonest due first`() = runBlocking<Unit> {
        val later = undertake(undertaking(workId = OTHER_WORK_ID, due = sessionEnd + 1.days))
        val sooner = undertake(undertaking())
        undertake(undertaking(workId = "work-session-released"))
        releaseAll(release(workId = "work-session-released"))

        val field = FieldService.over(handle.database, KnowledgeRepositoryImpl(handle.database), handle.repository)
            .snapshot(viewer = null)
            .getOrThrow()

        assertEquals(listOf(sooner, later), field.commitments)
        assertEquals(Ref<Subject>(PRINCIPAL), field.commitments.first().committer)
    }

    private companion object {
        const val PRINCIPAL = SubjectRegistry.PRINCIPAL_ID
        const val AGENT = "agent-a"
        const val WORK_ID = "work-session-1"
        const val OTHER_WORK_ID = "work-session-2"
    }
}
