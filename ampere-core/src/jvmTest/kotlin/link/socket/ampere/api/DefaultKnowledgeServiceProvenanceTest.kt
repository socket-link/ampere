package link.socket.ampere.api

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.api.internal.DefaultKnowledgeService
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.db.Database

/**
 * Provenance tests for [DefaultKnowledgeService] against a real [KnowledgeRepositoryImpl] and a
 * real door on one in-memory database — the stub used elsewhere in the API tests cannot show
 * what the schema and the event store do and do not record.
 *
 * Two facts, pinned separately. The *source hop*: the entry plus the one element it was
 * distilled from (AMPR-350), which is never followed because source ids are not knowledge ids.
 * The *event walk* (F11, AMPR-417): the row's `created_by_event`, then each envelope's
 * `caused_by`, back to an event with no cause.
 */
class DefaultKnowledgeServiceProvenanceTest {

    private val scope = CoroutineScope(SupervisorJob())

    private lateinit var door: InMemoryEventApi.Handle
    private lateinit var database: Database
    private lateinit var repository: KnowledgeRepositoryImpl
    private lateinit var service: KnowledgeService

    private val now = Clock.System.now()

    @BeforeTest
    fun setUp() {
        door = InMemoryEventApi.open(agentId = SDK_AGENT, scope = scope)
        database = door.database
        repository = KnowledgeRepositoryImpl(database)
        service = DefaultKnowledgeService(repository, door.api, door.repository)
    }

    @AfterTest
    fun tearDown() {
        door.close()
        scope.cancel()
    }

    private suspend fun store(knowledge: Knowledge, causedBy: EventId? = null): KnowledgeEntry =
        service.store(knowledge, causedBy = causedBy).getOrThrow()

    /** Publish a plain event through the door, caused by [causedBy], and return its id. */
    private suspend fun publish(id: EventId, causedBy: EventId? = null): EventId {
        door.api.publish(
            Event.TaskCreated(
                eventId = id,
                urgency = Urgency.LOW,
                timestamp = now,
                eventSource = EventSource.Agent(SDK_AGENT),
                taskId = "task-$id",
                description = "ancestor $id",
                assignedTo = null,
            ),
            causedBy = causedBy,
        ).getOrThrow()
        return id
    }

    @Test
    fun `provenance walks knowledge to its event and on through caused_by to the root`() = runBlocking<Unit> {
        val root = publish("root-event")
        val middle = publish("middle-event", causedBy = root)

        val stored = store(
            Knowledge.FromOutcome("outcome-1", "Retry with backoff", "Transient failures clear", now),
            causedBy = middle,
        )

        val origin = service.provenance(stored.id).getOrThrow()

        val producing = assertNotNull(stored.createdByEvent)
        assertEquals(listOf(producing, middle, root), origin.events.map { it.event.eventId })
        val knowledgeStored = origin.events.first().event as MemoryEvent.KnowledgeStored
        assertEquals(stored.id, knowledgeStored.knowledgeId)
        assertEquals(middle, origin.events.first().causedBy)
        assertNull(origin.events.last().causedBy)
        assertNull(origin.unresolvedEventId)
    }

    @Test
    fun `the row and the envelope agree on the producing event and the run`() = runBlocking<Unit> {
        val stored = service.store(
            Knowledge.FromTask("task-1", "Write it down", "Rows name their event", now),
            runId = "run-agree",
        ).getOrThrow()

        val producing = assertNotNull(stored.createdByEvent)
        val envelope = assertNotNull(door.repository.getStoredEventById(producing).getOrThrow())

        val row = database.knowledgeStoreQueries.getKnowledgeById(id = stored.id, viewer = null).executeAsOne()

        assertEquals(stored.id, (envelope.event as MemoryEvent.KnowledgeStored).knowledgeId)
        assertEquals(producing, row.created_by_event)
        assertEquals("run-agree", envelope.runId)
        assertEquals("run-agree", row.run_id)
        // Holder comes for free (F9): the door's identity is the event source and the holder.
        assertEquals(EventSource.Agent(SDK_AGENT), envelope.event.eventSource)
        assertEquals(SDK_AGENT, stored.holder.value)
    }

    @Test
    fun `a write with no cause has a one-event chain`() = runBlocking<Unit> {
        val stored = store(Knowledge.FromIdea("idea-1", "Consider retrying", "Backoff is worth trying", now))

        val origin = service.provenance(stored.id).getOrThrow()

        assertEquals(listOf(stored.createdByEvent), origin.events.map { it.event.eventId })
        assertNull(origin.unresolvedEventId)
    }

    @Test
    fun `a cause the store does not hold stops the walk and is reported`() = runBlocking<Unit> {
        val stored = store(
            Knowledge.FromIdea("idea-1", "Consider retrying", "Backoff is worth trying", now),
            causedBy = "pruned-event",
        )

        val origin = service.provenance(stored.id).getOrThrow()

        assertEquals(listOf(stored.createdByEvent), origin.events.map { it.event.eventId })
        assertEquals("pruned-event", origin.unresolvedEventId)
    }

    @Test
    fun `a row older than created_by_event has the source hop and no events`() = runBlocking<Unit> {
        insertLegacyRow(id = "knowledge-legacy", outcomeId = "outcome-legacy")

        val origin = service.provenance("knowledge-legacy").getOrThrow()

        assertEquals("outcome-legacy", origin.sourceId)
        assertTrue(origin.events.isEmpty())
        assertNull(origin.unresolvedEventId)
    }

    @Test
    fun `provenance returns the entry and the outcome it was distilled from`() = runBlocking<Unit> {
        val stored = store(
            Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Retry with backoff",
                learnings = "Transient auth failures clear on the second attempt",
                timestamp = now,
            ),
        )

        val origin = service.provenance(stored.id).getOrThrow()

        assertEquals(stored.id, origin.entry.id)
        assertEquals("Retry with backoff", origin.entry.approach)
        assertEquals(KnowledgeType.FROM_OUTCOME, origin.sourceType)
        assertEquals("outcome-1", origin.sourceId)
    }

    @Test
    fun `provenance reads the source id from the column its knowledge type names`() = runBlocking<Unit> {
        val expected = mapOf(
            KnowledgeType.FROM_IDEA to Knowledge.FromIdea("idea-1", "a", "l", now),
            KnowledgeType.FROM_OUTCOME to Knowledge.FromOutcome("outcome-1", "a", "l", now),
            KnowledgeType.FROM_PERCEPTION to Knowledge.FromPerception("perception-1", "a", "l", now),
            KnowledgeType.FROM_PLAN to Knowledge.FromPlan("plan-1", "a", "l", now),
            KnowledgeType.FROM_TASK to Knowledge.FromTask("task-1", "a", "l", now),
        )

        expected.forEach { (type, knowledge) ->
            val stored = store(knowledge)
            val origin = service.provenance(stored.id).getOrThrow()

            assertEquals(type, origin.sourceType, "source type for $type")
            assertEquals(
                type.name.removePrefix("FROM_").lowercase() + "-1",
                origin.sourceId,
                "source id for $type",
            )
        }
    }

    @Test
    fun `provenance reports one hop even when the whole lineage is in the store`() = runBlocking<Unit> {
        // The lineage a PROPEL run leaves behind: an idea, the plan made for it, and the
        // outcome of executing that plan, each distilled into its own knowledge entry.
        val ideaKnowledge = store(
            Knowledge.FromIdea("idea-1", "Consider retrying", "Backoff is worth trying", now),
        )
        val planKnowledge = store(
            Knowledge.FromPlan("plan-1", "Plan for idea-1", "Three steps, one tool call", now),
        )
        val outcomeKnowledge = store(
            Knowledge.FromOutcome("outcome-1", "Executed plan-1", "Retry cleared the failure", now),
        )

        val origin = service.provenance(outcomeKnowledge.id).getOrThrow()

        // One hop: this entry and the outcome it came from. The plan and idea steps are in
        // the store and are still not reachable from here — no row records a parent entry,
        // and "outcome-1" names an Outcome object that has no row of its own.
        assertEquals(outcomeKnowledge.id, origin.entry.id)
        assertEquals(KnowledgeType.FROM_OUTCOME, origin.sourceType)
        assertEquals("outcome-1", origin.sourceId)
        assertNull(repository.getKnowledgeById("outcome-1").getOrThrow())
        assertNotNull(repository.getKnowledgeById(planKnowledge.id).getOrThrow())
        assertNotNull(repository.getKnowledgeById(ideaKnowledge.id).getOrThrow())
    }

    @Test
    fun `provenance does not resolve a source id that happens to name another entry`() = runBlocking<Unit> {
        val first = store(Knowledge.FromIdea("idea-1", "First step", "Learned something", now))
        // Contrived, but it is the only shape the old walk could ever have followed: an entry
        // whose source id is literally another entry's knowledge id. Reporting the id as
        // recorded — rather than swapping in that entry — is the fix.
        val second = store(Knowledge.FromIdea(first.id, "Second step", "Learned more", now))

        val origin = service.provenance(second.id).getOrThrow()

        assertEquals(second.id, origin.entry.id)
        assertEquals("Second step", origin.entry.approach)
        assertEquals(first.id, origin.sourceId)
        assertEquals(KnowledgeType.FROM_IDEA, origin.sourceType)
    }

    @Test
    fun `provenance reports a null source id for a row that recorded none`() = runBlocking<Unit> {
        insertLegacyRow(id = "knowledge-no-source", outcomeId = null)

        val origin = service.provenance("knowledge-no-source").getOrThrow()

        assertEquals(KnowledgeType.FROM_OUTCOME, origin.sourceType)
        assertNull(origin.sourceId)
    }

    @Test
    fun `provenance fails when no entry has that id`() = runBlocking<Unit> {
        val result = service.provenance("nonexistent-id")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    /**
     * A row as a writer before AMPR-417 left it: no belief columns named, so it takes 10.sqm's
     * defaults — held by `system`, PUBLIC, no confidence, no producing event.
     */
    private fun insertLegacyRow(id: String, outcomeId: String?) {
        door.driver.execute(
            identifier = null,
            sql = """
                INSERT INTO KnowledgeStore (id, knowledge_type, approach, learnings, timestamp, outcome_id)
                VALUES (?, 'FROM_OUTCOME', 'Imported', 'Older rows predate the belief columns', ?, ?)
            """.trimIndent(),
            parameters = 3,
        ) {
            bindString(0, id)
            bindLong(1, now.toEpochMilliseconds())
            bindString(2, outcomeId)
        }
    }

    private companion object {
        const val SDK_AGENT = "sdk-test"
    }
}
