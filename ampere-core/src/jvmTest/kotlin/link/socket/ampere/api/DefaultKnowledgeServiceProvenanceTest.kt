package link.socket.ampere.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.internal.DefaultKnowledgeService
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.db.Database

/**
 * Provenance tests for [DefaultKnowledgeService] against a real [KnowledgeRepositoryImpl]
 * on an in-memory database — the stub used elsewhere in the API tests cannot show what the
 * schema does and does not record.
 *
 * `provenance()` used to claim an ordered trail of ideas, outcomes and perceptions and walk
 * it by passing each entry's source id back to `getKnowledgeById`. Source ids name
 * `Idea`/`Outcome`/`Perception`/`Plan`/`Task` objects, never knowledge rows, so the walk
 * always stopped on its first step. These tests pin the contract that replaced it: the entry
 * plus the one source it recorded (AMPR-350).
 */
class DefaultKnowledgeServiceProvenanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: KnowledgeRepositoryImpl
    private lateinit var service: KnowledgeService

    private val now = Clock.System.now()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        repository = KnowledgeRepositoryImpl(database)
        service = DefaultKnowledgeService(repository)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private suspend fun store(knowledge: Knowledge): KnowledgeEntry =
        service.store(knowledge).getOrThrow()

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
        database.knowledgeStoreQueries.insertKnowledge(
            id = "knowledge-no-source",
            knowledge_type = KnowledgeType.FROM_TASK.name,
            approach = "Imported without a source",
            learnings = "Older rows predate the source columns",
            timestamp = now.toEpochMilliseconds(),
            idea_id = null,
            outcome_id = null,
            perception_id = null,
            plan_id = null,
            task_id = null,
            task_type = null,
            complexity_level = null,
        )

        val origin = service.provenance("knowledge-no-source").getOrThrow()

        assertEquals(KnowledgeType.FROM_TASK, origin.sourceType)
        assertNull(origin.sourceId)
    }

    @Test
    fun `provenance fails when no entry has that id`() = runBlocking<Unit> {
        val result = service.provenance("nonexistent-id")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
