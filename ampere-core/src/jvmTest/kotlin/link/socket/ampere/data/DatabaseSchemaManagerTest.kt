package link.socket.ampere.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.data.DatabaseSchemaManager.SchemaState
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsAvailability
import link.socket.ampere.db.fts.FtsSchema

class DatabaseSchemaManagerTest {

    private val schemaVersion = Database.Schema.version
    private val tempDir: Path = createTempDirectory(prefix = "schema-manager")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `empty database is created at the schema version`() {
        inMemoryDriver().use { driver ->
            assertEquals(SchemaState.Created, DatabaseSchemaManager.ensure(driver).getOrThrow())
            assertEquals(schemaVersion, DatabaseSchemaManager.readUserVersion(driver))
        }
    }

    @Test
    fun `ensure on a current database changes nothing`() {
        inMemoryDriver().use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()

            assertEquals(SchemaState.Current, DatabaseSchemaManager.ensure(driver).getOrThrow())
            assertEquals(schemaVersion, DatabaseSchemaManager.readUserVersion(driver))
        }
    }

    @Test
    fun `created database reopens as current`() {
        val path = tempDir / "fresh.db"
        fileDriver(path).use { driver ->
            assertEquals(SchemaState.Created, DatabaseSchemaManager.ensure(driver).getOrThrow())
        }

        fileDriver(path).use { driver ->
            assertEquals(SchemaState.Current, DatabaseSchemaManager.ensure(driver).getOrThrow())
        }
    }

    @Test
    fun `legacy database without run_id migrates from v1 and keeps its rows`() {
        val path = legacyDatabase { driver ->
            driver.execute(
                null,
                "INSERT INTO EventStore VALUES ('evt-1', 'TaskCreated', 'agent-A', 42, '{}')",
                0,
            )
        }

        fileDriver(path).use { driver ->
            val state = DatabaseSchemaManager.ensure(driver).getOrThrow()

            assertEquals(SchemaState.Migrated(from = 1, to = schemaVersion), state)
            assertEquals(schemaVersion, DatabaseSchemaManager.readUserVersion(driver))
            for (table in listOf("EventStore", "KnowledgeStore", "OutcomeMemoryStore")) {
                assertTrue("run_id" in columns(driver, table), "$table should have run_id")
            }
            assertTrue(tableExists(driver, "Links"))
            assertTrue(tableExists(driver, "LinkGrants"))
            assertEquals(
                listOf("evt-1"),
                query(driver, "SELECT event_id FROM EventStore WHERE run_id IS NULL") { it.getString(0)!! },
            )
        }

        fileDriver(path).use { driver ->
            assertEquals(SchemaState.Current, DatabaseSchemaManager.ensure(driver).getOrThrow())
        }
    }

    @Test
    fun `migrated legacy tables match a freshly created schema column for column`() {
        val migratedTables = listOf("EventStore", "KnowledgeStore", "OutcomeMemoryStore", "Links", "LinkGrants") +
            UNMIGRATED_SQ_TABLES
        val fresh = inMemoryDriver().use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            migratedTables.associateWith { tableShape(driver, it) }
        }

        fileDriver(legacyDatabase()).use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()

            assertEquals(fresh, migratedTables.associateWith { tableShape(driver, it) })
        }
    }

    @Test
    fun `legacy knowledge reads back through generated queries after migration`() {
        val path = legacyDatabase { driver ->
            driver.execute(
                null,
                """
                INSERT INTO KnowledgeStore (
                    rowid, id, knowledge_type, approach, learnings, timestamp,
                    idea_id, task_type, complexity_level
                ) VALUES (7, 'k-1', 'FROM_IDEA', 'approach', 'learnings', 1000, 'idea-1', 'refactor', 'SIMPLE')
                """.trimIndent(),
                0,
            )
            driver.execute(null, "INSERT INTO KnowledgeTag VALUES ('k-1', 'legacy')", 0)
        }

        fileDriver(path).use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            val queries = Database(driver).knowledgeStoreQueries

            // getKnowledgeById is a SELECT *, so this fails if run_id was appended instead of
            // sitting where KnowledgeStore.sq declares it.
            val row = queries.getKnowledgeById("k-1").executeAsOne()
            assertNull(row.run_id)
            assertEquals("idea-1", row.idea_id)
            assertEquals("refactor", row.task_type)
            assertEquals("SIMPLE", row.complexity_level)
            assertEquals(listOf("legacy"), queries.getTagsForKnowledge("k-1").executeAsList())
            // KnowledgeFts is an external-content index keyed on rowid, so the rebuild must keep them.
            assertEquals(
                listOf(7L),
                query(driver, "SELECT rowid FROM KnowledgeStore WHERE id = 'k-1'") { it.getLong(0)!! },
            )
        }
    }

    @Test
    fun `knowledge appended run_id by the original 1_sqm is rebuilt in declared order`() {
        // What Android and iOS databases look like after the original 1.sqm, which appended
        // KnowledgeStore.run_id with ADD COLUMN, and then 2.sqm: stamped v3, run_id last.
        val path = legacyDatabase { driver ->
            ORIGINAL_1_SQM.forEach { driver.execute(null, it, 0) }
            Database.Schema.migrate(driver, 2, 3)
            driver.execute(
                null,
                """
                INSERT INTO KnowledgeStore (
                    rowid, id, knowledge_type, approach, learnings, timestamp, run_id,
                    idea_id, outcome_id, perception_id, plan_id, task_id, task_type, complexity_level
                ) VALUES (
                    42, 'k-1', 'FROM_IDEA', 'approach', 'learnings', 1000, 'run-1',
                    'idea-1', 'outcome-1', 'perception-1', 'plan-1', 'task-1', 'refactor', 'SIMPLE'
                )
                """.trimIndent(),
                0,
            )
        }

        fileDriver(path).use { driver ->
            DatabaseSchemaManager.writeUserVersion(driver, 3)
            assertEquals("run_id", columns(driver, "KnowledgeStore").last())

            assertEquals(
                SchemaState.Migrated(from = 3, to = schemaVersion),
                DatabaseSchemaManager.ensure(driver).getOrThrow(),
            )

            val fresh = inMemoryDriver().use { freshDriver ->
                DatabaseSchemaManager.ensure(freshDriver).getOrThrow()
                tableShape(freshDriver, "KnowledgeStore")
            }
            assertEquals(fresh, tableShape(driver, "KnowledgeStore"))

            val row = Database(driver).knowledgeStoreQueries.getKnowledgeById("k-1").executeAsOne()
            assertEquals("k-1", row.id)
            assertEquals("FROM_IDEA", row.knowledge_type)
            assertEquals("approach", row.approach)
            assertEquals("learnings", row.learnings)
            assertEquals(1000L, row.timestamp)
            assertEquals("run-1", row.run_id)
            assertEquals("idea-1", row.idea_id)
            assertEquals("outcome-1", row.outcome_id)
            assertEquals("perception-1", row.perception_id)
            assertEquals("plan-1", row.plan_id)
            assertEquals("task-1", row.task_id)
            assertEquals("refactor", row.task_type)
            assertEquals("SIMPLE", row.complexity_level)
            assertEquals(
                listOf(42L),
                query(driver, "SELECT rowid FROM KnowledgeStore WHERE id = 'k-1'") { it.getLong(0)!! },
            )
        }
    }

    @Test
    fun `legacy database gains the tables added to sq files without a migration`() {
        fileDriver(legacyDatabase()).use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()

            for (table in UNMIGRATED_SQ_TABLES) {
                assertTrue(tableExists(driver, table), "$table should exist")
            }
            // Its knowledge_chunks triggers are what failed on legacy databases before 5.sqm.
            assertEquals(FtsAvailability.Available, FtsSchema.install(driver))
        }
    }

    @Test
    fun `legacy database that already has the unmigrated tables keeps their rows`() {
        // What the old path built after those tables landed: a v3 schema that already has them,
        // user_version 0. Migrating 5 -> 6 runs only 5.sqm, which adds exactly those tables.
        val path = legacyDatabase { driver ->
            Database.Schema.migrate(driver, 1, 3)
            Database.Schema.migrate(driver, 5, 6)
            driver.execute(
                null,
                "INSERT INTO knowledge_documents VALUES ('doc-1', 'Title', NULL, 1000, 'hash')",
                0,
            )
            driver.execute(null, "INSERT INTO PlugGrants VALUES ('plug-1', '{}', 1000)", 0)
        }

        fileDriver(path).use { driver ->
            assertEquals(3L, DatabaseSchemaManager.inferLegacyVersion(driver))
            DatabaseSchemaManager.ensure(driver).getOrThrow()

            assertEquals(
                listOf("doc-1"),
                query(driver, "SELECT id FROM knowledge_documents") { it.getString(0)!! },
            )
            assertEquals(
                listOf("plug-1"),
                query(driver, "SELECT plug_id FROM PlugGrants") { it.getString(0)!! },
            )
        }
    }

    @Test
    fun `orphaned PluginGrants table is replaced by PlugGrants`() {
        // Databases built between the grant schema landing and its rename (AMPR-220).
        val path = legacyDatabase { driver ->
            Database.Schema.migrate(driver, 1, 3)
            driver.execute(
                null,
                """
                CREATE TABLE PluginGrants (
                  plugin_id TEXT NOT NULL,
                  permission_json TEXT NOT NULL,
                  granted_at INTEGER NOT NULL,
                  PRIMARY KEY (plugin_id, permission_json)
                )
                """.trimIndent(),
                0,
            )
            driver.execute(null, "CREATE INDEX idx_plugin_grants_plugin_id ON PluginGrants(plugin_id)", 0)
        }

        fileDriver(path).use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()

            assertFalse(tableExists(driver, "PluginGrants"))
            assertTrue(
                query(driver, "SELECT 1 FROM sqlite_master WHERE name = 'idx_plugin_grants_plugin_id'") { true }
                    .isEmpty(),
            )
            assertTrue(tableExists(driver, "PlugGrants"))
        }
    }

    @Test
    fun `legacy database with run_id but no Links migrates from v2`() {
        // What the old path built between 1.sqm and 2.sqm: run_id present, no Links.
        val path = legacyDatabase { driver -> Database.Schema.migrate(driver, 1, 2) }

        fileDriver(path).use { driver ->
            assertEquals(2L, DatabaseSchemaManager.inferLegacyVersion(driver))
            assertEquals(
                SchemaState.Migrated(from = 2, to = schemaVersion),
                DatabaseSchemaManager.ensure(driver).getOrThrow(),
            )
            assertTrue(tableExists(driver, "Links"))
        }
    }

    @Test
    fun `legacy database with run_id and Links is inferred at v3`() {
        val path = legacyDatabase { driver -> Database.Schema.migrate(driver, 1, 3) }

        fileDriver(path).use { driver ->
            assertEquals(3L, DatabaseSchemaManager.inferLegacyVersion(driver))
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            assertEquals(schemaVersion, DatabaseSchemaManager.readUserVersion(driver))
        }
    }

    @Test
    fun `migrating from v3 numbers legacy events by timestamp and stamps recorded_at`() {
        // A v3 database: run_id and Links present, none of the envelope columns yet.
        val path = legacyDatabase { driver ->
            Database.Schema.migrate(driver, 1, 3)
            for ((id, timestamp) in listOf("evt-middle" to 2_000L, "evt-last" to 3_000L, "evt-first" to 1_000L)) {
                driver.execute(
                    null,
                    "INSERT INTO EventStore (event_id, event_type, source_id, timestamp, payload) " +
                        "VALUES ('$id', 'TaskCreated', 'agent-A', $timestamp, '{}')",
                    0,
                )
            }
        }

        fileDriver(path).use { driver ->
            assertEquals(3L, DatabaseSchemaManager.inferLegacyVersion(driver))
            assertEquals(
                SchemaState.Migrated(from = 3, to = schemaVersion),
                DatabaseSchemaManager.ensure(driver).getOrThrow(),
            )

            val bySequence = query(driver, "SELECT event_id, sequence FROM EventStore ORDER BY sequence") {
                it.getString(0)!! to it.getLong(1)!!
            }
            assertEquals(listOf("evt-first" to 1L, "evt-middle" to 2L, "evt-last" to 3L), bySequence)
            assertEquals(3, bySequence.map { it.second }.toSet().size)
            assertEquals(
                listOf(3L),
                query(driver, "SELECT COUNT(*) FROM EventStore WHERE recorded_at = timestamp") { it.getLong(0)!! },
            )
            assertTrue("idx_event_sequence" in indexes(driver, "EventStore"))
            assertTrue("idx_event_caused_by" in indexes(driver, "EventStore"))
        }
    }

    @Test
    fun `failed migration leaves the legacy database untouched`() {
        // 1.sqm alters OutcomeMemoryStore last, so without it the migration fails partway through.
        val path = legacyDatabase { driver -> driver.execute(null, "DROP TABLE OutcomeMemoryStore", 0) }

        fileDriver(path).use { driver ->
            assertTrue(DatabaseSchemaManager.ensure(driver).isFailure)

            assertEquals(0L, DatabaseSchemaManager.readUserVersion(driver))
            assertFalse("run_id" in columns(driver, "EventStore"))
            assertFalse("run_id" in columns(driver, "KnowledgeStore"))
            assertFalse(tableExists(driver, "KnowledgeStore_v2"))
        }
    }

    @Test
    fun `database newer than the schema fails without being touched`() {
        inMemoryDriver().use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            DatabaseSchemaManager.writeUserVersion(driver, schemaVersion + 1)

            val failure = DatabaseSchemaManager.ensure(driver).exceptionOrNull()

            assertIs<IllegalStateException>(failure)
            assertEquals(
                "database is at v${schemaVersion + 1}, newer than schema v$schemaVersion",
                failure.message,
            )
            assertEquals(schemaVersion + 1, DatabaseSchemaManager.readUserVersion(driver))
        }
    }

    private fun inMemoryDriver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    private fun fileDriver(path: Path) = JdbcSqliteDriver("jdbc:sqlite:$path")

    /**
     * Builds a database file the way the old bare `Schema.create` path left one before 1.sqm:
     * the tables 1.sqm and 2.sqm touch, without `run_id` or `Links`, and `user_version` still 0.
     */
    private fun legacyDatabase(setUp: (SqlDriver) -> Unit = {}): Path {
        val path = tempDir / "legacy-${System.nanoTime()}.db"
        fileDriver(path).use { driver ->
            PRE_RUN_ID_DDL.forEach { driver.execute(null, it.trimIndent(), 0) }
            setUp(driver)
            assertEquals(0L, DatabaseSchemaManager.readUserVersion(driver))
        }
        return path
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        query(driver, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'") { true }
            .isNotEmpty()

    private fun indexes(driver: SqlDriver, table: String): List<String> =
        query(driver, "SELECT name FROM pragma_index_list('$table')") { it.getString(0)!! }

    private fun columns(driver: SqlDriver, table: String): List<String> =
        query(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid") { it.getString(0)!! }

    /** Columns in declared order with type/nullability/key, then each index with its key columns. */
    private fun tableShape(driver: SqlDriver, table: String): List<String> {
        val columns = query(
            driver,
            "SELECT name, type, \"notnull\", pk FROM pragma_table_info('$table') ORDER BY cid",
        ) { "${it.getString(0)} ${it.getString(1)} notnull=${it.getLong(2)} pk=${it.getLong(3)}" }
        val indexes = query(driver, "SELECT name FROM pragma_index_list('$table') ORDER BY name") {
            it.getString(0)!!
        }.map { index ->
            val keys = query(
                driver,
                "SELECT name, \"desc\" FROM pragma_index_xinfo('$index') WHERE key = 1 ORDER BY seqno",
            ) { "${it.getString(0)}${if (it.getLong(1) == 1L) " DESC" else ""}" }
            "$index(${keys.joinToString()})"
        }
        return columns + indexes
    }

    private fun <T> query(driver: SqlDriver, sql: String, read: (SqlCursor) -> T): List<T> =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val rows = mutableListOf<T>()
                while (cursor.next().value) rows += read(cursor)
                QueryResult.Value(rows)
            },
            parameters = 0,
        ).value

    private companion object {
        /** 1.sqm as first shipped (AMPR-152), before AMPR-333 rewrote it to rebuild KnowledgeStore. */
        val ORIGINAL_1_SQM = listOf(
            "ALTER TABLE EventStore ADD COLUMN run_id TEXT",
            "CREATE INDEX IF NOT EXISTS idx_event_run_id ON EventStore(run_id)",
            "ALTER TABLE KnowledgeStore ADD COLUMN run_id TEXT",
            "CREATE INDEX IF NOT EXISTS idx_knowledge_run_id ON KnowledgeStore(run_id)",
            "ALTER TABLE OutcomeMemoryStore ADD COLUMN run_id TEXT",
            "CREATE INDEX IF NOT EXISTS idx_outcome_run_id ON OutcomeMemoryStore(run_id)",
        )

        /** Tables added to `.sq` files without a migration until 5.sqm created them. */
        val UNMIGRATED_SQ_TABLES = listOf(
            "knowledge_documents",
            "knowledge_chunks",
            "knowledge_embeddings",
            "document_scopes",
            "PlugGrants",
        )

        val PRE_RUN_ID_DDL = listOf(
            """
            CREATE TABLE EventStore (
              event_id TEXT PRIMARY KEY NOT NULL,
              event_type TEXT NOT NULL,
              source_id TEXT NOT NULL,
              timestamp INTEGER NOT NULL,
              payload TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_event_type ON EventStore(event_type)",
            "CREATE INDEX idx_timestamp ON EventStore(timestamp)",
            """
            CREATE TABLE KnowledgeStore (
                id TEXT PRIMARY KEY NOT NULL,
                knowledge_type TEXT NOT NULL,
                approach TEXT NOT NULL,
                learnings TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                idea_id TEXT,
                outcome_id TEXT,
                perception_id TEXT,
                plan_id TEXT,
                task_id TEXT,
                task_type TEXT,
                complexity_level TEXT
            )
            """,
            "CREATE INDEX idx_knowledge_type ON KnowledgeStore(knowledge_type)",
            "CREATE INDEX idx_knowledge_timestamp ON KnowledgeStore(timestamp DESC)",
            "CREATE INDEX idx_knowledge_task_type ON KnowledgeStore(task_type)",
            "CREATE INDEX idx_knowledge_complexity ON KnowledgeStore(complexity_level)",
            """
            CREATE TABLE KnowledgeTag (
                knowledge_id TEXT NOT NULL,
                tag TEXT NOT NULL,
                PRIMARY KEY (knowledge_id, tag),
                FOREIGN KEY (knowledge_id) REFERENCES KnowledgeStore(id) ON DELETE CASCADE
            )
            """,
            "CREATE INDEX idx_knowledge_tag_tag ON KnowledgeTag(tag)",
            "CREATE INDEX idx_knowledge_tag_knowledge_id ON KnowledgeTag(knowledge_id)",
            """
            CREATE TABLE OutcomeMemoryStore (
                id TEXT PRIMARY KEY NOT NULL,
                ticket_id TEXT NOT NULL,
                executor_id TEXT NOT NULL,
                approach TEXT NOT NULL,
                success INTEGER NOT NULL CHECK (success IN (0, 1)),
                execution_duration_ms INTEGER NOT NULL,
                files_changed INTEGER NOT NULL,
                error_message TEXT,
                timestamp INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_outcome_ticket_id ON OutcomeMemoryStore(ticket_id)",
            "CREATE INDEX idx_outcome_executor_id ON OutcomeMemoryStore(executor_id)",
            "CREATE INDEX idx_outcome_timestamp ON OutcomeMemoryStore(timestamp DESC)",
            "CREATE INDEX idx_outcome_success ON OutcomeMemoryStore(success)",
        )
    }
}
