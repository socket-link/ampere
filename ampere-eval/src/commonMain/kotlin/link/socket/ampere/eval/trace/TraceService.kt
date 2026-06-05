package link.socket.ampere.eval.trace

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.db.EvalDatabase
import link.socket.ampere.util.ioDispatcher

/**
 * Persistence boundary for [Trace]s, backed by the `ampere-eval` SQLDelight
 * database. All fallible operations return [Result]; no exceptions cross this
 * boundary.
 *
 * Note: AMPR-183 names this parameter `AmpereDatabase`. RECON-trace.md (AMPR-188)
 * established that the real ampere-core handle is `link.socket.ampere.db.Database`
 * (production schema). The eval Trace table is an eval-only concern and lives in
 * this module's own [EvalDatabase] — keeping production code free of any
 * dependency on `ampere-eval`, per the ticket's conventions.
 */
class TraceService(
    private val db: EvalDatabase,
    private val json: Json = DEFAULT_JSON,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    private val queries get() = db.traceQueries

    private val eventsSerializer = ListSerializer(TraceEvent.serializer())

    /** Persist [trace] atomically (insert or overwrite by id). */
    suspend fun save(trace: Trace): Result<Unit> = withContext(dispatcher) {
        runCatching {
            queries.upsertTrace(
                id = trace.id,
                run_id = trace.runId,
                arc_id = trace.arcId,
                created_at = trace.createdAt,
                event_count = trace.size.toLong(),
                events_json = json.encodeToString(eventsSerializer, trace.events),
            )
            Unit
        }
    }

    /** Load a full [Trace] by id. Fails if no trace with [traceId] exists. */
    suspend fun load(traceId: String): Result<Trace> = withContext(dispatcher) {
        runCatching {
            queries.selectById(traceId) { id, runId, arcId, createdAt, _, eventsJson ->
                Trace(
                    id = id,
                    runId = runId,
                    arcId = arcId,
                    createdAt = createdAt,
                    events = json.decodeFromString(eventsSerializer, eventsJson),
                )
            }.executeAsOneOrNull()
                ?: error("Trace not found: $traceId")
        }
    }

    /** List trace metadata, newest first; optionally scoped to a single [arcId]. */
    suspend fun list(arcId: String? = null): Result<List<TraceSummary>> = withContext(dispatcher) {
        runCatching {
            val mapper = { id: String, runId: String, arc: String, createdAt: Long, eventCount: Long ->
                TraceSummary(
                    id = id,
                    runId = runId,
                    arcId = arc,
                    createdAt = createdAt,
                    eventCount = eventCount.toInt(),
                )
            }
            if (arcId == null) {
                queries.selectSummaries(mapper).executeAsList()
            } else {
                queries.selectSummariesByArcId(arcId, mapper).executeAsList()
            }
        }
    }
}
