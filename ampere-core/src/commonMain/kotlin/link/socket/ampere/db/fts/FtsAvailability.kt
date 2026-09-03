package link.socket.ampere.db.fts

/**
 * Whether the SQLite build backing a [link.socket.ampere.db.Database] has the FTS5 module
 * compiled in, and therefore whether the three full-text search indexes
 * (`knowledge_chunks_fts`, `KnowledgeFts`, `OutcomeMemoryFts`) exist and can be queried.
 *
 * FTS5 support is no longer part of `Database.Schema.create()` (see [FtsSchema]): a SQLite
 * build missing the module must not take the rest of the schema down with it. Instead, FTS
 * availability is probed once per driver via [FtsSchema.install] and recorded here so search
 * callers can route to the `*Like` fallback queries by checking a flag, rather than by
 * catching a query exception on every call.
 */
sealed interface FtsAvailability {

    /** FTS5 virtual tables and triggers were created successfully; FTS queries can run. */
    data object Available : FtsAvailability

    /**
     * FTS5 is not available on this SQLite build. [reason] is the exception raised while
     * creating the virtual tables, kept for diagnostics — callers should route search to the
     * `*Like` fallback queries instead of retrying FTS.
     */
    data class Unavailable(val reason: Throwable) : FtsAvailability
}
