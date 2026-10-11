package link.socket.ampere.agents.domain.knowledge

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.reasoning.Confidence
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.db.fts.FtsSchema
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.subject.SubjectRegistry
import link.socket.ampere.trace.ArcTraceProjection

/**
 * The belief columns on `KnowledgeStore` (AMPR-417): who holds a row (F9), how confident the
 * holder is (F10), who may read it (F12), and which event recorded it (F11).
 *
 * Two agents share one database, each writing and reading through its own door, the way a
 * team's agents do in production. Every read path a Subject can take is checked against the
 * viewer predicate, and the trace projection against the replay exclusion.
 */
class BeliefColumnsTest {

    private val scope = CoroutineScope(SupervisorJob())

    private lateinit var door: InMemoryEventApi.Handle
    private lateinit var repository: KnowledgeRepository
    private lateinit var alice: AgentMemoryService
    private lateinit var bob: AgentMemoryService

    private val now = Clock.System.now()

    @BeforeTest
    fun setUp() {
        door = InMemoryEventApi.open(agentId = ALICE, scope = scope)
        FtsSchema.install(door.driver)
        repository = KnowledgeRepositoryImpl(door.database, door.driver)
        alice = AgentMemoryService(ALICE, repository, door.api)
        val bobsDoor = AgentEventApi(
            agentId = BOB,
            eventRepository = door.repository,
            eventSerialBus = door.bus,
        )
        bob = AgentMemoryService(BOB, repository, bobsDoor)
    }

    @AfterTest
    fun tearDown() {
        door.close()
        scope.cancel()
    }

    private fun knowledge(
        approach: String,
        learnings: String = "learned about $approach",
        confidence: Confidence? = null,
    ) = Knowledge.FromOutcome(
        outcomeId = "outcome-$approach",
        approach = approach,
        learnings = learnings,
        timestamp = now,
        confidence = confidence,
    )

    private suspend fun AgentMemoryService.write(
        approach: String,
        sensitivity: Sensitivity,
        runId: String? = null,
    ): KnowledgeEntry = storeKnowledge(
        knowledge = knowledge(approach, learnings = "lighthouse $approach"),
        tags = listOf(TAG),
        taskType = TASK_TYPE,
        runId = runId,
        sensitivity = sensitivity,
    ).getOrThrow()

    // ==================== Holder (F9) ====================

    @Test
    fun `a write through an agent's memory is held by that agent`() = runBlocking<Unit> {
        val entry = alice.write("holder", Sensitivity.PUBLIC)

        assertEquals(Ref(ALICE), entry.holder)
        assertEquals(Ref(ALICE), assertNotNull(repository.getKnowledgeById(entry.id).getOrThrow()).holder)
    }

    @Test
    fun `a row older than the belief columns reads as the system's`() = runBlocking<Unit> {
        door.driver.execute(
            identifier = null,
            sql = """
                INSERT INTO KnowledgeStore (id, knowledge_type, approach, learnings, timestamp, outcome_id)
                VALUES ('knowledge-legacy', 'FROM_OUTCOME', 'legacy', 'from before 10.sqm', 1000, 'outcome-legacy')
            """.trimIndent(),
            parameters = 0,
        )

        val legacy = assertNotNull(repository.getKnowledgeById("knowledge-legacy").getOrThrow())

        assertEquals(SubjectRegistry.SYSTEM_REF, legacy.holder)
        assertEquals(Sensitivity.PUBLIC, legacy.sensitivity)
        assertNull(legacy.confidence)
        assertNull(legacy.createdByEvent)
    }

    // ==================== Confidence (F10) ====================

    @Test
    fun `a self-reported level is stored as its F10 mapping`() = runBlocking<Unit> {
        val entry = alice.storeKnowledge(knowledge("reported", confidence = Confidence.HIGH)).getOrThrow()

        assertEquals(0.75, entry.confidence)
        assertEquals(0.75, assertNotNull(alice.recallKnowledgeById(entry.id).getOrThrow()).confidence)
        val event = door.repository.getStoredEventById(assertNotNull(entry.createdByEvent)).getOrThrow()
        assertEquals(0.75, (assertNotNull(event).event as MemoryEvent.KnowledgeStored).confidence)
    }

    @Test
    fun `a measured value is stored as given and never remapped`() = runBlocking<Unit> {
        val entry = alice.storeKnowledge(
            knowledge("measured", confidence = Confidence.LOW),
            confidence = BeliefConfidence.Measured(0.83),
        ).getOrThrow()

        assertEquals(0.83, entry.confidence)
    }

    @Test
    fun `a write that reported nothing has no confidence`() = runBlocking<Unit> {
        val entry = alice.storeKnowledge(knowledge("silent")).getOrThrow()

        assertNull(entry.confidence)
    }

    @Test
    fun `a measured confidence outside zero to one is refused`() {
        assertFailsWith<IllegalArgumentException> { BeliefConfidence.Measured(1.2) }
    }

    @Test
    fun `the evaluator's confidence is no longer written into the learnings`() = runBlocking<Unit> {
        val entry = alice.storeKnowledge(knowledge("prose", confidence = Confidence.MEDIUM)).getOrThrow()

        assertTrue("Confidence" !in entry.learnings)
        assertEquals(0.5, entry.confidence)
    }

    // ==================== Viewer exclusions (F12) ====================

    @Test
    fun `a PROTECTED row held by A is invisible to every read B makes`() = runBlocking<Unit> {
        val protectedRow = alice.write("mornings", Sensitivity.PROTECTED)
        val viewer = Ref<Subject>(BOB)

        assertNull(bob.recallKnowledgeById(protectedRow.id).getOrThrow())
        assertTrue(bob.findKnowledgeByType(KnowledgeType.FROM_OUTCOME).getOrThrow().isEmpty())
        assertTrue(bob.findKnowledgeByTaskType(TASK_TYPE).getOrThrow().isEmpty())
        assertTrue(bob.findKnowledgeByTag(TAG).getOrThrow().isEmpty())
        assertTrue(
            bob.recallRelevantKnowledge(
                MemoryContext(taskType = TASK_TYPE, tags = setOf(TAG), description = "lighthouse mornings"),
            ).getOrThrow().isEmpty(),
        )
        assertTrue(repository.findKnowledgeByTags(listOf(TAG), viewer = viewer).getOrThrow().isEmpty())
        assertTrue(repository.findSimilarKnowledge("lighthouse mornings", viewer = viewer).getOrThrow().isEmpty())
        assertTrue(
            repository.findKnowledgeByTimeRange(
                fromTimestamp = Instant.DISTANT_PAST,
                toTimestamp = Instant.DISTANT_FUTURE,
                viewer = viewer,
            ).getOrThrow().isEmpty(),
        )
        assertTrue(repository.searchKnowledgeByContext(taskType = TASK_TYPE, viewer = viewer).getOrThrow().isEmpty())
        assertTrue(
            repository.searchKnowledgeByContext(tags = listOf(TAG), viewer = viewer).getOrThrow().isEmpty(),
        )

        // The holder still reads its own.
        assertEquals(protectedRow.id, alice.recallKnowledgeById(protectedRow.id).getOrThrow()?.id)
        assertEquals(listOf(protectedRow.id), alice.findKnowledgeByTag(TAG).getOrThrow().map { it.id })
        assertEquals(
            listOf(protectedRow.id),
            repository.findSimilarKnowledge("lighthouse mornings", viewer = Ref(ALICE)).getOrThrow().map { it.id },
        )
    }

    @Test
    fun `a PRIVATE row is its holder's alone and a PUBLIC row is everyone's`() = runBlocking<Unit> {
        val privateRow = alice.write("private", Sensitivity.PRIVATE)
        val publicRow = alice.write("public", Sensitivity.PUBLIC)

        assertEquals(listOf(publicRow.id), bob.findKnowledgeByTaskType(TASK_TYPE).getOrThrow().map { it.id })
        assertEquals(
            setOf(privateRow.id, publicRow.id),
            alice.findKnowledgeByTaskType(TASK_TYPE).getOrThrow().map { it.id }.toSet(),
        )
    }

    @Test
    fun `a read that names no viewer sees PUBLIC rows only`() = runBlocking<Unit> {
        alice.write("protected", Sensitivity.PROTECTED)
        alice.write("private", Sensitivity.PRIVATE)
        val publicRow = alice.write("public", Sensitivity.PUBLIC)

        assertEquals(listOf(publicRow.id), repository.findKnowledgeByTaskType(TASK_TYPE).getOrThrow().map { it.id })
    }

    @Test
    fun `B's page is not shortened by rows it cannot see`() = runBlocking<Unit> {
        repeat(3) { alice.write("hidden-$it", Sensitivity.PROTECTED) }
        val publicRow = alice.write("visible", Sensitivity.PUBLIC)

        // The predicate is in the query, ahead of the LIMIT: a limit of one still finds the row.
        assertEquals(listOf(publicRow.id), bob.findKnowledgeByTaskType(TASK_TYPE, limit = 1).getOrThrow().map { it.id })
    }

    // ==================== Replay exclusion (F12) ====================

    @Test
    fun `a PROTECTED row is absent from a replay read`() = runBlocking<Unit> {
        val protectedRow = alice.write("mornings", Sensitivity.PROTECTED, runId = RUN)
        val privateRow = alice.write("private", Sensitivity.PRIVATE, runId = RUN)
        val publicRow = alice.write("public", Sensitivity.PUBLIC, runId = RUN)

        val trace = ArcTraceProjection(door.database).project(RUN).getOrThrow()
        val written = trace.phases.flatMap { it.memoryWrites }

        assertEquals(setOf(privateRow.id, publicRow.id), written.map { it.id }.toSet())
        assertTrue(written.none { it.approach == "mornings" || it.learnings?.contains("mornings") == true })
        assertEquals(
            setOf(privateRow.id, publicRow.id),
            door.database.knowledgeStoreQueries.findKnowledgeByRunId(RUN).executeAsList().map { it.id }.toSet(),
        )
        assertNull(door.database.knowledgeStoreQueries.getReplayableKnowledgeById(protectedRow.id).executeAsOneOrNull())
    }

    @Test
    fun `a PROTECTED write's event records the write and none of its content`() = runBlocking<Unit> {
        val protectedRow = alice.write("mornings", Sensitivity.PROTECTED)

        val stored = assertNotNull(
            door.repository.getStoredEventById(assertNotNull(protectedRow.createdByEvent)).getOrThrow(),
        )
        val event = stored.event as MemoryEvent.KnowledgeStored

        assertEquals(protectedRow.id, event.knowledgeId)
        assertEquals(Sensitivity.PROTECTED, event.sensitivity)
        assertNull(event.approach)
        assertNull(event.learnings)
        assertTrue(event.tags.isEmpty())
    }

    @Test
    fun `recalling one's own PROTECTED row does not copy it into the recall event`() = runBlocking<Unit> {
        val protectedRow = alice.write("mornings", Sensitivity.PROTECTED)
        val publicRow = alice.write("public", Sensitivity.PUBLIC)

        val recalled = alice.recallRelevantKnowledge(
            MemoryContext(taskType = TASK_TYPE, tags = setOf(TAG), description = "lighthouse"),
        ).getOrThrow()
        assertEquals(setOf(protectedRow.id, publicRow.id), recalled.map { it.entry.id }.toSet())

        val event = door.repository.getEventsByType(MemoryEvent.KnowledgeRecalled.EVENT_TYPE).getOrThrow()
            .filterIsInstance<MemoryEvent.KnowledgeRecalled>()
            .single()
        assertEquals(2, event.resultsFound)
        assertEquals(listOf("public"), event.retrievedKnowledge.map { it.approach })
    }

    private companion object {
        const val ALICE = "agent-alice"
        const val BOB = "agent-bob"
        const val TAG = "belief"
        const val TASK_TYPE = "generic"
        const val RUN = "run-beliefs"
    }
}
