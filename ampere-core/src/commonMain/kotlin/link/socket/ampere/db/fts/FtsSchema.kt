package link.socket.ampere.db.fts

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger

/**
 * Creates the FTS5 virtual tables and sync triggers that `Database.Schema.create()`
 * deliberately no longer declares (see AMPR-325).
 *
 * The three FTS5 modules ([KNOWLEDGE_CHUNKS_FTS_STATEMENTS], [KNOWLEDGE_FTS_STATEMENTS],
 * [OUTCOME_MEMORY_FTS_STATEMENTS]) used to live in `.sq` schema files as `CREATE VIRTUAL
 * TABLE` + trigger DDL. SQLDelight emits `Schema.create()` as one block of statements, and on
 * Android that block runs inside `SQLiteOpenHelper.onCreate`'s transaction: a single `no such
 * module: fts5` failure there rolls back the *entire* schema, taking Links, knowledge, memory
 * and the persisted event bus down with search. [install] runs the same DDL as a separate,
 * guarded step so a missing FTS5 module degrades search instead of destroying the database.
 *
 * Callers should invoke [install] once per driver, after `Database.Schema.create(driver)` has
 * already run and returned (i.e. outside any transaction that DDL failure could roll back),
 * and record the resulting [FtsAvailability] to route search queries.
 */
object FtsSchema {

    private val KNOWLEDGE_CHUNKS_FTS_STATEMENTS = listOf(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts USING fts5(
            id UNINDEXED,
            text,
            content=knowledge_chunks,
            content_rowid=rowid
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_insert
        AFTER INSERT ON knowledge_chunks BEGIN
            INSERT INTO knowledge_chunks_fts(rowid, id, text)
            VALUES (new.rowid, new.id, new.text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_delete
        AFTER DELETE ON knowledge_chunks BEGIN
            INSERT INTO knowledge_chunks_fts(knowledge_chunks_fts, rowid, id, text)
            VALUES ('delete', old.rowid, old.id, old.text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS knowledge_chunks_fts_update
        AFTER UPDATE ON knowledge_chunks BEGIN
            INSERT INTO knowledge_chunks_fts(knowledge_chunks_fts, rowid, id, text)
            VALUES ('delete', old.rowid, old.id, old.text);
            INSERT INTO knowledge_chunks_fts(rowid, id, text)
            VALUES (new.rowid, new.id, new.text);
        END
        """.trimIndent(),
    )

    // External-content FTS5 tables read their column values back out of the content table by
    // name, so every indexed column must exist there under the same name. The delete/update
    // triggers use the `'delete'` command with the old values: a plain `DELETE FROM <fts>` makes
    // FTS5 re-read the content row, which in an AFTER trigger is already gone or changed, so
    // the old tokens are never removed (AMPR-355).
    private val KNOWLEDGE_FTS_STATEMENTS = listOf(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS KnowledgeFts USING fts5(
            id UNINDEXED,
            approach,
            learnings,
            content=KnowledgeStore,
            content_rowid=rowid
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS knowledge_fts_insert AFTER INSERT ON KnowledgeStore BEGIN
            INSERT INTO KnowledgeFts(rowid, id, approach, learnings)
            VALUES (new.rowid, new.id, new.approach, new.learnings);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS knowledge_fts_delete AFTER DELETE ON KnowledgeStore BEGIN
            INSERT INTO KnowledgeFts(KnowledgeFts, rowid, id, approach, learnings)
            VALUES ('delete', old.rowid, old.id, old.approach, old.learnings);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS knowledge_fts_update AFTER UPDATE ON KnowledgeStore BEGIN
            INSERT INTO KnowledgeFts(KnowledgeFts, rowid, id, approach, learnings)
            VALUES ('delete', old.rowid, old.id, old.approach, old.learnings);
            INSERT INTO KnowledgeFts(rowid, id, approach, learnings)
            VALUES (new.rowid, new.id, new.approach, new.learnings);
        END
        """.trimIndent(),
    )

    private val OUTCOME_MEMORY_FTS_STATEMENTS = listOf(
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
            INSERT INTO OutcomeMemoryFts(OutcomeMemoryFts, rowid, id, approach)
            VALUES ('delete', old.rowid, old.id, old.approach);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS outcome_fts_update AFTER UPDATE ON OutcomeMemoryStore BEGIN
            INSERT INTO OutcomeMemoryFts(OutcomeMemoryFts, rowid, id, approach)
            VALUES ('delete', old.rowid, old.id, old.approach);
            INSERT INTO OutcomeMemoryFts(rowid, id, approach)
            VALUES (new.rowid, new.id, new.approach);
        END
        """.trimIndent(),
    )

    /**
     * An FTS5 table whose definition changed after databases were already created with the old
     * one. `CREATE ... IF NOT EXISTS` never replaces an existing definition, so [install]
     * detects the old DDL in `sqlite_master` and rebuilds the table from its content table.
     *
     * @property staleMarkers substrings (matched case-insensitively) that only occur in the
     * superseded `sqlite_master.sql` of [table] or one of its [triggers].
     */
    private class RepairableFtsTable(
        val table: String,
        val triggers: List<String>,
        val statements: List<String>,
        val staleMarkers: List<String>,
    )

    private val REPAIRABLE_TABLES = listOf(
        RepairableFtsTable(
            table = "KnowledgeFts",
            triggers = listOf("knowledge_fts_insert", "knowledge_fts_delete", "knowledge_fts_update"),
            statements = KNOWLEDGE_FTS_STATEMENTS,
            // Pre-AMPR-355: a `knowledge_id` column KnowledgeStore doesn't have, and plain
            // `DELETE FROM` delete/update triggers.
            staleMarkers = listOf("knowledge_id", "DELETE FROM KnowledgeFts"),
        ),
        RepairableFtsTable(
            table = "OutcomeMemoryFts",
            triggers = listOf("outcome_fts_insert", "outcome_fts_delete", "outcome_fts_update"),
            statements = OUTCOME_MEMORY_FTS_STATEMENTS,
            // Pre-AMPR-355: plain `DELETE FROM` delete/update triggers.
            staleMarkers = listOf("DELETE FROM OutcomeMemoryFts"),
        ),
    )

    /**
     * All FTS5 DDL, in dependency order. If the first `CREATE VIRTUAL TABLE ... USING fts5`
     * statement fails because the module is missing, every later statement would fail
     * identically (fts5 registration is global to the SQLite build, not per-table), so
     * [install] stops at the first failure rather than attempting the rest.
     */
    private val ALL_STATEMENTS =
        KNOWLEDGE_CHUNKS_FTS_STATEMENTS + KNOWLEDGE_FTS_STATEMENTS + OUTCOME_MEMORY_FTS_STATEMENTS

    private val logger = Logger.withTag("FtsSchema")

    /**
     * Creates the FTS5 virtual tables and sync triggers on [driver], guarded against a missing
     * fts5 module.
     *
     * Idempotent: every statement is `IF NOT EXISTS`, so calling this more than once for the
     * same driver (e.g. once from a driver factory and again lazily from a repository) is
     * harmless. Tables created with a superseded definition are dropped, recreated and rebuilt
     * from their content table first (see [RepairableFtsTable]); that repair only runs once,
     * since the recreated definitions no longer match any stale marker.
     *
     * @throws Exception if DDL creation fails for a reason other than a missing fts5 module —
     * that is a genuine schema bug and must not be silently swallowed as "no FTS".
     */
    fun install(driver: SqlDriver): FtsAvailability {
        return try {
            for (fts in REPAIRABLE_TABLES) {
                if (isStale(driver, fts)) repair(driver, fts)
            }
            for (statement in ALL_STATEMENTS) {
                driver.execute(identifier = null, sql = statement, parameters = 0)
            }
            FtsAvailability.Available
        } catch (cause: Exception) {
            if (isMissingFts5Module(cause)) {
                logger.w(cause) {
                    "FTS5 module is unavailable on this SQLite build; search will use the " +
                        "LIKE fallback instead of ranked full-text matches."
                }
                FtsAvailability.Unavailable(cause)
            } else {
                throw cause
            }
        }
    }

    private fun isStale(driver: SqlDriver, fts: RepairableFtsTable): Boolean {
        val names = (listOf(fts.table) + fts.triggers).joinToString { "'$it'" }
        val definitions = driver.executeQuery(
            identifier = null,
            sql = "SELECT sql FROM sqlite_master WHERE name IN ($names)",
            mapper = { cursor ->
                val sql = buildList {
                    while (cursor.next().value) {
                        cursor.getString(0)?.let(::add)
                    }
                }
                QueryResult.Value(sql)
            },
            parameters = 0,
        ).value
        return definitions.any { definition ->
            fts.staleMarkers.any { marker -> definition.contains(marker, ignoreCase = true) }
        }
    }

    /**
     * Drops [fts]'s table and triggers, recreates them from the current DDL and re-indexes every
     * existing content row. A `'rebuild'` against the old `KnowledgeFts` definition fails (it
     * reads a column the content table doesn't have), so the drop has to come first. Runs in one
     * transaction so a failure part-way leaves the old, still-searchable definition in place.
     */
    private fun repair(driver: SqlDriver, fts: RepairableFtsTable) {
        logger.i { "Replacing a superseded ${fts.table} definition and rebuilding its index." }
        object : TransacterImpl(driver) {}.transaction {
            for (trigger in fts.triggers) {
                driver.execute(identifier = null, sql = "DROP TRIGGER IF EXISTS $trigger", parameters = 0)
            }
            driver.execute(identifier = null, sql = "DROP TABLE IF EXISTS ${fts.table}", parameters = 0)
            for (statement in fts.statements) {
                driver.execute(identifier = null, sql = statement, parameters = 0)
            }
            driver.execute(
                identifier = null,
                sql = "INSERT INTO ${fts.table}(${fts.table}) VALUES ('rebuild')",
                parameters = 0,
            )
        }
    }

    private fun isMissingFts5Module(cause: Throwable?): Boolean {
        if (cause == null) return false
        return cause.message?.contains("no such module: fts5", ignoreCase = true) == true ||
            isMissingFts5Module(cause.cause)
    }
}
