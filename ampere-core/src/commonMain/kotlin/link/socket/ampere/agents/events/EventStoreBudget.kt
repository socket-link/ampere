package link.socket.ampere.agents.events

/**
 * Size contract enforced on every `EventStore` write by [EventRepository.saveEvent] (AMPR-301).
 *
 * The production event log is the disk-full generator the AMPR-291 fate table found sitting
 * inside the disk-full interruption mode (finding F3): every event published through the door
 * becomes a row, and before this there was no cap, no drop policy, and no rotation. The
 * vocabulary here is deliberately the same as `ampere-eval`'s `TraceBudget` (AMPR-267) — a
 * per-event bound and a whole-record bound, both enforced write-side, both leaving a marker —
 * so the two records of a run are governed by one design rather than two.
 *
 * Where it differs from `TraceBudget`, and why:
 *
 * - The bounds are larger. A trace is a secondary copy of a bounded recording window; the
 *   `EventStore` is the primary durable record of everything the process ever did.
 * - The whole-record bound is enforced by **rotation**, not by dropping the tail. A trace is
 *   finalized once, so it drops its trailing events; the store is append-only forever, so the
 *   newest event must always be admitted and the *oldest* rows are what give way. Dropping the
 *   tail instead would mean the store stopped recording the moment it filled.
 * - Dropping leaves a durable marker (`EventStorePruning`) rather than a count on an in-memory
 *   object, because there is no enclosing record to hang a count on.
 */
object EventStoreBudget {

    /**
     * Max chars any single string leaf of a serialized event may carry before
     * `truncateStringLeaves` cuts it. Four times `TraceBudget.MAX_STRING_FIELD_CHARS`: a
     * truncated leaf in a trace can be re-read from the store, but a leaf cut here is gone for
     * good, so the store keeps more of it.
     */
    const val MAX_STRING_FIELD_CHARS = 8_000

    /**
     * Max serialized bytes one row's `payload` should occupy. 32 KiB is the canon projection
     * budget established by AMPR-262's `CanonWorkEntitiesTest` — the largest single artifact
     * the system already agrees to hold in one place.
     *
     * Best-effort, unlike [MAX_STORE_BYTES]: it is enforced by cutting oversized *string
     * leaves*, so an event that exceeds it through sheer field count rather than one long
     * free-text field is stored whole rather than corrupted. [MAX_STORE_BYTES] is the hard
     * backstop underneath it.
     */
    const val MAX_EVENT_BYTES = 32 * 1024

    /**
     * High-water mark: cumulative `payload` bytes across `EventStore` above which the oldest
     * rows are pruned. 64 MiB is roughly two million typical events, far more history than any
     * consumer reads back, and small enough that the sweep's `SUM` stays cheap.
     *
     * `payload` is the only column measured. It is the dominant term by an order of magnitude —
     * every other column is an id, a type name, or an integer — and measuring it is one scan
     * rather than a page-level accounting of the whole file.
     */
    const val MAX_STORE_BYTES = 64L * 1024 * 1024

    /**
     * Low-water mark: a sweep that fires prunes down to here, not merely back under
     * [MAX_STORE_BYTES]. Pruning to the high-water mark would put the next write over it again,
     * turning every subsequent write into a sweep; the 16 MiB gap amortizes that away.
     */
    const val PRUNE_TO_BYTES = 48L * 1024 * 1024

    /**
     * Bytes written between budget sweeps. The sweep costs one `SUM` over `payload`, so it is
     * not run per write; at this interval a store at budget is scanned about once every four
     * thousand events. The first save of each [EventRepository] sweeps unconditionally, so a
     * process that opens an already-oversized database does not have to write 4 MiB before
     * anything is reclaimed.
     */
    const val SWEEP_INTERVAL_BYTES = 4L * 1024 * 1024

    /**
     * Rows a pruning pass inspects per round trip. The pass deletes by `sequence` cutoff in
     * batches so that reclaiming 16 MiB never materializes every doomed row at once.
     *
     * Sized for the pass that hurts: the first one on a database that predates this budget.
     * The CLI store has been seen at 1.1 GB and ~1M rows (see `4.sqm`), and that pass has to
     * walk nearly all of them. At 5,000 rows a trip — about 80 KB of sequence/size pairs — it
     * takes a couple of hundred index-ordered round trips rather than a couple of thousand.
     */
    const val PRUNE_BATCH_SIZE = 5_000L

    /**
     * Pruning markers kept in `EventStorePruning`. The marker table is the one thing a ticket
     * about unbounded growth must not leave unbounded, so each pass trims it to this many rows,
     * newest first.
     */
    const val MAX_PRUNING_RECORDS = 1_000L
}
