package link.socket.ampere.db.fts

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import link.socket.ampere.db.memory.KnowledgeStore
import link.socket.ampere.db.memory.OutcomeMemoryStore

/**
 * A single chunk of imported knowledge, as returned by [SqlDriver.searchKnowledgeChunksByText].
 *
 * `knowledge_chunks` itself is still a SQLDelight-typed table (see `Knowledge.sq`), but the
 * FTS5 join can't be: `knowledge_chunks_fts` isn't declared in the `.sq` schema (see
 * [FtsSchema]), so SQLDelight has nothing to type-check `MATCH`/`rank` against. This mirrors
 * the shape of the removed `knowledgeFTSQueries.searchChunksByText` generated query.
 */
data class KnowledgeChunkMatch(
    val id: String,
    val documentId: String,
    val chunkIndex: Long,
    val text: String,
    val charStart: Long,
    val charEnd: Long,
)

/**
 * FTS5 keyword match against chunk text, ranked by relevance.
 *
 * Only valid to call when [FtsSchema.install] returned [FtsAvailability.Available] for this
 * driver — the `knowledge_chunks_fts` virtual table must already exist.
 */
fun SqlDriver.searchKnowledgeChunksByText(query: String, limit: Long): List<KnowledgeChunkMatch> =
    executeQuery(
        identifier = null,
        sql = """
            SELECT c.id, c.document_id, c.chunk_index, c.text, c.char_start, c.char_end
            FROM knowledge_chunks c
            INNER JOIN knowledge_chunks_fts fts ON c.rowid = fts.rowid
            WHERE knowledge_chunks_fts MATCH ?
            ORDER BY fts.rank
            LIMIT ?
        """.trimIndent(),
        mapper = { cursor ->
            val results = buildList {
                while (cursor.next().value) {
                    add(
                        KnowledgeChunkMatch(
                            id = cursor.getString(0)!!,
                            documentId = cursor.getString(1)!!,
                            chunkIndex = cursor.getLong(2)!!,
                            text = cursor.getString(3)!!,
                            charStart = cursor.getLong(4)!!,
                            charEnd = cursor.getLong(5)!!,
                        ),
                    )
                }
            }
            QueryResult.Value(results)
        },
        parameters = 2,
        binders = {
            bindString(0, query)
            bindLong(1, limit)
        },
    ).value

/**
 * FTS5 keyword match against knowledge approach/learnings text, ranked by relevance.
 *
 * Only valid to call when [FtsSchema.install] returned [FtsAvailability.Available] for this
 * driver — the `KnowledgeFts` virtual table must already exist.
 */
fun SqlDriver.searchKnowledgeByText(query: String, limit: Long): List<KnowledgeStore> =
    executeQuery(
        identifier = null,
        sql = """
            SELECT k.* FROM KnowledgeStore k
            INNER JOIN KnowledgeFts fts ON k.rowid = fts.rowid
            WHERE KnowledgeFts MATCH ?
            ORDER BY fts.rank, k.timestamp DESC
            LIMIT ?
        """.trimIndent(),
        mapper = { cursor ->
            val results = buildList {
                while (cursor.next().value) {
                    add(
                        KnowledgeStore(
                            id = cursor.getString(0)!!,
                            knowledge_type = cursor.getString(1)!!,
                            approach = cursor.getString(2)!!,
                            learnings = cursor.getString(3)!!,
                            timestamp = cursor.getLong(4)!!,
                            run_id = cursor.getString(5),
                            idea_id = cursor.getString(6),
                            outcome_id = cursor.getString(7),
                            perception_id = cursor.getString(8),
                            plan_id = cursor.getString(9),
                            task_id = cursor.getString(10),
                            task_type = cursor.getString(11),
                            complexity_level = cursor.getString(12),
                        ),
                    )
                }
            }
            QueryResult.Value(results)
        },
        parameters = 2,
        binders = {
            bindString(0, query)
            bindLong(1, limit)
        },
    ).value

/**
 * FTS5 keyword match against outcome approach text, ranked by relevance.
 *
 * Only valid to call when [FtsSchema.install] returned [FtsAvailability.Available] for this
 * driver — the `OutcomeMemoryFts` virtual table must already exist.
 */
fun SqlDriver.findSimilarOutcomesByText(query: String, limit: Long): List<OutcomeMemoryStore> =
    executeQuery(
        identifier = null,
        sql = """
            SELECT o.* FROM OutcomeMemoryStore o
            JOIN OutcomeMemoryFts fts ON o.rowid = fts.rowid
            WHERE OutcomeMemoryFts MATCH ?
            ORDER BY fts.rank, o.timestamp DESC
            LIMIT ?
        """.trimIndent(),
        mapper = { cursor ->
            val results = buildList {
                while (cursor.next().value) {
                    add(
                        OutcomeMemoryStore(
                            id = cursor.getString(0)!!,
                            ticket_id = cursor.getString(1)!!,
                            executor_id = cursor.getString(2)!!,
                            approach = cursor.getString(3)!!,
                            success = cursor.getLong(4)!!,
                            execution_duration_ms = cursor.getLong(5)!!,
                            files_changed = cursor.getLong(6)!!,
                            error_message = cursor.getString(7),
                            timestamp = cursor.getLong(8)!!,
                            run_id = cursor.getString(9),
                        ),
                    )
                }
            }
            QueryResult.Value(results)
        },
        parameters = 2,
        binders = {
            bindString(0, query)
            bindLong(1, limit)
        },
    ).value
