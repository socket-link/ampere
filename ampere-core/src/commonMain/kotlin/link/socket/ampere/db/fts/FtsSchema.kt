package link.socket.ampere.db.fts

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

    private val KNOWLEDGE_FTS_STATEMENTS = listOf(
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
     * harmless.
     *
     * @throws Exception if DDL creation fails for a reason other than a missing fts5 module —
     * that is a genuine schema bug and must not be silently swallowed as "no FTS".
     */
    fun install(driver: SqlDriver): FtsAvailability {
        return try {
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

    private fun isMissingFts5Module(cause: Throwable?): Boolean {
        if (cause == null) return false
        return cause.message?.contains("no such module: fts5", ignoreCase = true) == true ||
            isMissingFts5Module(cause.cause)
    }
}
