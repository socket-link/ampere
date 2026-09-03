package link.socket.ampere.db.fts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.db.Database
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the AMPR-325 degradation path against a SQLite build that genuinely lacks FTS5.
 *
 * Unlike [link.socket.ampere.api.AmpereFromEnvironmentAndroidTest] (which builds the schema
 * over xerial's `sqlite-jdbc` because the *bundled* FTS5 SQLite's native `.so` can't load on a
 * desktop JVM), this test uses [AndroidSqliteDriver] with the framework's default
 * `SupportSQLiteOpenHelper.Factory` under Robolectric. Robolectric's SQLite native runtime has
 * no FTS5 module, making it a faithful stand-in for a real device running Android's system
 * SQLite instead of the bundled build from [link.socket.ampere.data.createAndroidDriver] —
 * exactly the scenario AMPR-325 must not let take the database down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FtsSchemaAndroidTest {

    private lateinit var driver: AndroidSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // No `factory` argument: this is Robolectric's own framework SQLite, which has no
        // fts5 module — the default Android system SQLite would behave identically.
        driver = AndroidSqliteDriver(schema = Database.Schema, context = context, name = null)
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `schema creation succeeds without the fts5 module`() {
        // Schema.create() ran as part of AndroidSqliteDriver's lazy open above; reaching this
        // point at all (rather than throwing during setUp) is the regression this guards
        // against — the FTS5 DDL used to be part of that same transaction.
        assertNotNull(database.knowledgeStoreQueries.getAllKnowledge().executeAsList())
    }

    @Test
    fun `FtsSchema install reports unavailable instead of throwing`() {
        val availability = FtsSchema.install(driver)
        assertIs<FtsAvailability.Unavailable>(availability)
    }

    @Test
    fun `non-FTS tables stay intact and readable`() {
        FtsSchema.install(driver)

        database.knowledgeQueries.insertDocument(
            id = "doc-1",
            title = "Manual",
            source_uri = null,
            imported_at = 1_000L,
            content_hash = "hash-1",
        )
        database.knowledgeQueries.insertChunk(
            id = "chunk-1",
            document_id = "doc-1",
            chunk_index = 0,
            text = "Lighthouses guide ships safely along the rocky coast.",
            char_start = 0,
            char_end = 54,
        )

        val chunks = database.knowledgeQueries.getChunksByDocument("doc-1").executeAsList()
        assertTrue(chunks.any { it.id == "chunk-1" })
    }

    @Test
    fun `findSimilarKnowledge falls back to LIKE ranking when FTS5 is unavailable`() = runBlocking {
        val repo = KnowledgeRepositoryImpl(database, driver)
        val now = Clock.System.now()

        repo.storeKnowledge(
            knowledge = Knowledge.FromTask(
                taskId = "task-1",
                approach = "Add input validation to UserRepository",
                learnings = "Validation belongs at the boundary",
                timestamp = now,
            ),
            tags = emptyList(),
        ).getOrThrow()

        val results = repo.findSimilarKnowledge("validation", limit = 5).getOrThrow()

        assertTrue(results.any { it.approach.contains("validation", ignoreCase = true) })
    }

    @Test
    fun `findSimilarOutcomes falls back to LIKE ranking when FTS5 is unavailable`() = runBlocking {
        val repo = OutcomeMemoryRepositoryImpl(database, driver)
        val now = Clock.System.now()
        val outcome = ExecutionOutcome.NoChanges.Failure(
            executorId = "executor-1",
            ticketId = "ticket-1",
            taskId = "task-1",
            executionStartTimestamp = now,
            executionEndTimestamp = now,
            message = "Add validation to OrderRepository",
        )

        repo.recordOutcome(
            ticketId = "ticket-1",
            executorId = "executor-1",
            approach = "Add validation to OrderRepository",
            outcome = outcome,
            timestamp = now,
        ).getOrThrow()

        val results = repo.findSimilarOutcomes("validation", limit = 5).getOrThrow()

        assertTrue(results.any { it.approach.contains("validation", ignoreCase = true) })
    }
}
