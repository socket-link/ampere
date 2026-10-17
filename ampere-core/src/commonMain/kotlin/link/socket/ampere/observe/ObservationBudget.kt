package link.socket.ampere.observe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Retention contract enforced on every `ObservationStore` write by
 * [SqlDelightObservationStore.insert] (AMPR-465).
 *
 * `EventStoreBudget` (AMPR-301) transcribed onto the Field's Environment branch. The two tables
 * grow by the same mechanism — every perceive writes rows, nothing read them back out — and
 * `ObservationStore` is the worse case of the two: its key is per *perceive*, not per entity, so
 * an Arc that perceives the same twenty calendar events every morning writes twenty new rows
 * every morning, and its rows cite events that `EventStoreBudget` rotates out from under them.
 * Before this there was no `DELETE` in the file at all.
 *
 * Where it differs from `EventStoreBudget`, and why:
 *
 * - The whole-table bound is a **row count**, not a byte sum. Every row is the same shape — a
 *   one-line statement plus ids — so rows are a faithful proxy for bytes (~200 bytes each), and
 *   `COUNT(*)` is the cheaper scan.
 * - There is an **age cap** as well. An event is a record of what happened and is true forever;
 *   an Observation is a reading of a moving world (`Decay.HalfLife`'s own KDoc), and one past
 *   its horizon is a stale fact kept at the cost of a fresh one.
 * - The sweep honours the row's own **expiry**: a `Decay.ExpiresAt` in the past is dropped on
 *   the same pass, so a consumer that needs per-kind retention passes `decay` and gets it.
 *
 * What it does not do: dropping is oldest-first by `created_at`, never by sensitivity.
 * `PROTECTED` rows are kept no longer and no shorter than `PRIVATE` ones — F12 is a visibility
 * rule, not a retention rule. And no row is exempt for being cited: `Provenance.createdBy` on
 * every primitive names an event, never an Observation row, so pruning one can never dangle a
 * Belief or Commitment.
 */
object ObservationBudget {

    /**
     * Age past which a row is dropped, measured from its `created_at` (the perceive's
     * `recorded_at`) against the store's clock. Ninety days: a quarter covers every weekly and
     * monthly cadence an Arc runs on, with room to answer "what did my Arcs see last month",
     * and an Observation older than that is a reading of a world that has moved on.
     *
     * Also the default [link.socket.ampere.field.Decay.HalfLife] a `toObservation` row carries,
     * so the row says what the store will do to it and a reader deriving strength from `decay`
     * agrees with the sweep.
     */
    val MAX_AGE: Duration = 90.days

    /**
     * High-water mark: rows across all holders above which the oldest are pruned. At ~200
     * bytes a row this is ~60 MB, parity with `EventStoreBudget.MAX_STORE_BYTES`. Sized so a
     * heavy consumer — ten scheduled Arcs, hourly, twenty entities a page, ~430 000 rows in
     * [MAX_AGE] — is bounded by this cap only when it loops, not when it works; and far more
     * than `FieldService.DEFAULT_BELIEF_LIMIT` (100) ever reads back.
     */
    const val MAX_ROWS = 300_000L

    /**
     * Low-water mark: a cap pass prunes down to here, not merely back under [MAX_ROWS].
     * Pruning to the high-water mark would put the next write over it again, turning every
     * subsequent sweep into a cap pass; the gap is the same fifth `EventStoreBudget` keeps.
     */
    const val PRUNE_TO_ROWS = 240_000L

    /**
     * Rows written between budget sweeps. A sweep is three deletes and a `COUNT(*)`, so it is
     * not run per row; at this interval a store at budget is swept about once per dozen
     * perceive pages. The first insert of each [SqlDelightObservationStore] sweeps
     * unconditionally, so a process that opens an already-oversized database reclaims on its
     * first write rather than after 256 more rows.
     */
    const val SWEEP_INTERVAL_INSERTS = 256L

    /**
     * Pruning markers kept in `ObservationStorePruning`. The marker table is the one thing a
     * ticket about unbounded growth must not leave unbounded, so each pass trims it to this many
     * rows, newest first.
     */
    const val MAX_PRUNING_RECORDS = 1_000L
}
