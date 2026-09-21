package link.socket.ampere.db.fts

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.ampere.db.Database

/**
 * AMPR-355: the `KnowledgeFts` / `OutcomeMemoryFts` sync triggers must survive updates and
 * deletes on their content tables, and databases created with the old DDL must be repaired by
 * [FtsSchema.install]. xerial's sqlite-jdbc compiles fts5 in, so FTS is always available here.
 */
class FtsSchemaTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `KnowledgeFts follows insert then update then delete`() {
        assertIs<FtsAvailability.Available>(FtsSchema.install(driver))

        insertKnowledge(id = "k1", approach = "Refactor the parser", learnings = "alpha")
        assertEquals(listOf("k1"), driver.searchKnowledgeByText("alpha", limit = 5).map { it.id })

        exec("UPDATE KnowledgeStore SET learnings = 'bravo' WHERE id = 'k1'")
        assertEquals(listOf("k1"), driver.searchKnowledgeByText("bravo", limit = 5).map { it.id })
        assertTrue(driver.searchKnowledgeByText("alpha", limit = 5).isEmpty())
        assertEquals(listOf("k1"), driver.searchKnowledgeByText("parser", limit = 5).map { it.id })

        database.knowledgeStoreQueries.deleteKnowledge("k1")
        assertTrue(driver.searchKnowledgeByText("bravo", limit = 5).isEmpty())
        assertTrue(ftsRowids("KnowledgeFts", "bravo OR parser").isEmpty())
    }

    @Test
    fun `OutcomeMemoryFts follows insert then update then delete`() {
        assertIs<FtsAvailability.Available>(FtsSchema.install(driver))

        insertOutcome(id = "o1", approach = "alpha approach")
        assertEquals(listOf("o1"), driver.findSimilarOutcomesByText("alpha", limit = 5).map { it.id })

        exec("UPDATE OutcomeMemoryStore SET approach = 'bravo approach' WHERE id = 'o1'")
        assertEquals(listOf("o1"), driver.findSimilarOutcomesByText("bravo", limit = 5).map { it.id })
        assertTrue(driver.findSimilarOutcomesByText("alpha", limit = 5).isEmpty())

        database.outcomeMemoryStoreQueries.deleteOutcome("o1")
        assertTrue(driver.findSimilarOutcomesByText("bravo", limit = 5).isEmpty())
        // The index itself must drop the tokens, not just the join: a later row reusing the
        // rowid would otherwise match them.
        assertTrue(ftsRowids("OutcomeMemoryFts", "bravo").isEmpty())
    }

    @Test
    fun `install replaces the pre-AMPR-355 definitions and rebuilds the index`() {
        // A row that predates the FTS table, like one carried over by the 1.sqm KnowledgeStore
        // rebuild: only a 'rebuild' can index it.
        insertKnowledge(id = "k1", approach = "Refactor the parser", learnings = "lighthouse")
        for (statement in LEGACY_DDL) exec(statement)
        insertOutcome(id = "o1", approach = "stale charlie")
        // The legacy plain-DELETE trigger leaves "charlie" in the index.
        database.outcomeMemoryStoreQueries.deleteOutcome("o1")
        assertEquals(1, ftsRowids("OutcomeMemoryFts", "charlie").size)
        insertOutcome(id = "o2", approach = "delta approach")

        assertIs<FtsAvailability.Available>(FtsSchema.install(driver))

        val definitions = schemaSql()
        assertFalse(definitions.any { it.contains("knowledge_id") }, definitions.joinToString("\n"))
        assertFalse(definitions.any { it.contains("DELETE FROM", ignoreCase = true) }, definitions.joinToString("\n"))

        assertEquals(listOf("k1"), driver.searchKnowledgeByText("lighthouse", limit = 5).map { it.id })
        assertEquals(listOf("o2"), driver.findSimilarOutcomesByText("delta", limit = 5).map { it.id })
        assertTrue(ftsRowids("OutcomeMemoryFts", "charlie").isEmpty())

        // The repaired triggers handle updates and deletes.
        exec("UPDATE KnowledgeStore SET learnings = 'beacon' WHERE id = 'k1'")
        assertEquals(listOf("k1"), driver.searchKnowledgeByText("beacon", limit = 5).map { it.id })
        database.knowledgeStoreQueries.deleteKnowledge("k1")
        assertTrue(driver.searchKnowledgeByText("beacon", limit = 5).isEmpty())
    }

    @Test
    fun `install is idempotent after a repair`() {
        for (statement in LEGACY_DDL) exec(statement)
        FtsSchema.install(driver)
        val afterRepair = schemaSql()

        insertKnowledge(id = "k1", approach = "Refactor the parser", learnings = "lighthouse")
        assertIs<FtsAvailability.Available>(FtsSchema.install(driver))

        assertEquals(afterRepair, schemaSql())
        assertEquals(listOf("k1"), driver.searchKnowledgeByText("lighthouse", limit = 5).map { it.id })
    }

    private fun insertKnowledge(id: String, approach: String, learnings: String) {
        database.knowledgeStoreQueries.insertKnowledge(
            id = id,
            knowledge_type = "FROM_TASK",
            approach = approach,
            learnings = learnings,
            timestamp = 1_000L,
            idea_id = null,
            outcome_id = null,
            perception_id = null,
            plan_id = null,
            task_id = "task-$id",
            task_type = null,
            complexity_level = null,
        )
    }

    private fun insertOutcome(id: String, approach: String) {
        database.outcomeMemoryStoreQueries.insertOutcome(
            id = id,
            ticket_id = "ticket-$id",
            executor_id = "executor-1",
            approach = approach,
            success = 1L,
            execution_duration_ms = 10L,
            files_changed = 1L,
            error_message = null,
            timestamp = 1_000L,
        )
    }

    private fun exec(sql: String) {
        driver.execute(identifier = null, sql = sql, parameters = 0)
    }

    private fun ftsRowids(table: String, match: String): List<Long> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT rowid FROM $table WHERE $table MATCH ?",
            mapper = { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) })
            },
            parameters = 1,
            binders = { bindString(0, match) },
        ).value

    private fun schemaSql(): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT sql FROM sqlite_master
                WHERE name IN (
                    'KnowledgeFts', 'knowledge_fts_insert', 'knowledge_fts_delete', 'knowledge_fts_update',
                    'OutcomeMemoryFts', 'outcome_fts_insert', 'outcome_fts_delete', 'outcome_fts_update'
                )
                ORDER BY name
            """.trimIndent(),
            mapper = { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)!!) })
            },
            parameters = 0,
        ).value

    private companion object {
        /** The `KnowledgeFts` / `OutcomeMemoryFts` DDL `FtsSchema` shipped before AMPR-355. */
        val LEGACY_DDL = listOf(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS KnowledgeFts USING fts5(
                knowledge_id UNINDEXED,
                approach,
                learnings,
                content=KnowledgeStore,
                content_rowid=rowid
            )
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS knowledge_fts_insert AFTER INSERT ON KnowledgeStore BEGIN
                INSERT INTO KnowledgeFts(rowid, knowledge_id, approach, learnings)
                VALUES (new.rowid, new.id, new.approach, new.learnings);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS knowledge_fts_delete AFTER DELETE ON KnowledgeStore BEGIN
                DELETE FROM KnowledgeFts WHERE rowid = old.rowid;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS knowledge_fts_update AFTER UPDATE ON KnowledgeStore BEGIN
                DELETE FROM KnowledgeFts WHERE rowid = old.rowid;
                INSERT INTO KnowledgeFts(rowid, knowledge_id, approach, learnings)
                VALUES (new.rowid, new.id, new.approach, new.learnings);
            END
            """.trimIndent(),
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS OutcomeMemoryFts USING fts5(
                id UNINDEXED,
                approach,
                content=OutcomeMemoryStore,
                content_rowid=rowid
            )
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS outcome_fts_insert AFTER INSERT ON OutcomeMemoryStore BEGIN
                INSERT INTO OutcomeMemoryFts(rowid, id, approach)
                VALUES (new.rowid, new.id, new.approach);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS outcome_fts_delete AFTER DELETE ON OutcomeMemoryStore BEGIN
                DELETE FROM OutcomeMemoryFts WHERE rowid = old.rowid;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS outcome_fts_update AFTER UPDATE ON OutcomeMemoryStore BEGIN
                DELETE FROM OutcomeMemoryFts WHERE rowid = old.rowid;
                INSERT INTO OutcomeMemoryFts(rowid, id, approach)
                VALUES (new.rowid, new.id, new.approach);
            END
            """.trimIndent(),
        )
    }
}
