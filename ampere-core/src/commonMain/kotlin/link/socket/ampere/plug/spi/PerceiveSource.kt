package link.socket.ampere.plug.spi

import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.adapter.CanonConversionFailure
import link.socket.ampere.link.LinkId

/**
 * A source a Plug enumerates through: native objects in, [T] out.
 *
 * This is the *operation* half of the chassis boundary —
 * [link.socket.ampere.link.LinkOperation.PERCEIVE]. An adapter such as
 * [link.socket.ampere.canon.adapter.ReadableCanonAdapter] is the *projection*
 * underneath it: an implementation typically enumerates native objects
 * itself (a Mail query, a Photos fetch, a Notification Center read) and
 * calls an adapter to project each one into [T].
 *
 * ## `T` is unconstrained, not bound to [link.socket.ampere.canon.CanonEntity]
 *
 * The obvious bound is wrong. Some P0 Plugs are deliberately canon-external
 * — the closed v1 canon set has no member for a notification, a pasteboard
 * payload, or recognised text, and inventing one is a versioned canon
 * change, not a Plug's declaration ([link.socket.ampere.canon.CanonType]).
 * Their manifests correctly land on `emits = emptySet()`, which [emits]
 * mirrors. Bind this interface to `CanonEntity` and those Plugs have no base
 * to extend here — they would grow a second, parallel operation path, which
 * is the fragmentation this SPI exists to prevent. [emits] stays the
 * machine-readable contract; empty means canon-external.
 *
 * ## No [kotlinx.coroutines.flow.Flow]
 *
 * [link.socket.ampere.link.LinkOperation] already settled how push-shaped
 * sources (HealthKit observers, location updates, APNS delivery) reconcile
 * with a pull boundary: the source buffers internally, and buffered
 * emissions surface as discrete observations at the next [perceive] call —
 * they are not streamed. A source with a genuinely push-shaped native API
 * buffers and returns a [PerceivePage] like every other source; do not reach
 * for `Flow` here.
 *
 * ## Predicates are never dropped
 *
 * A source that cannot evaluate a [PerceivePredicate] — an unknown field, a
 * relation its native API cannot filter on, a form it never pushes down —
 * MUST return it in [PerceivePage.residual]. It must never silently drop it,
 * and never fail the call over it. A dropped predicate turns an
 * over-approximate page into one that claims to be exact, and a caller that
 * trusts it dispatches work it must not dispatch (the AMPR-289 ready-queue
 * returned a blocked and a gated ticket exactly this way). Returning every
 * predicate as residual is always correct — [PerceivePage.unfiltered] is that
 * path. `PerceiveSourceContract` in `ampere-core-test-fixtures` checks this
 * for any implementation.
 */
interface PerceiveSource<out T> {

    /**
     * The canon types this source can emit. Empty for a canon-external
     * source — never inferred, always the source's own honest declaration
     * of what [perceive] can produce.
     */
    val emits: Set<CanonType>

    /**
     * Enumerate native objects and project them into [T].
     *
     * Every [PerceiveQuery.predicates] entry must come back on each page in
     * exactly one of [PerceivePage.evaluated] or [PerceivePage.residual] — see
     * "Predicates are never dropped" above.
     */
    suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<T>>
}

/**
 * What to enumerate and how far.
 *
 * @property linkId Required — consent and provenance are both keyed on it,
 *   so a query with no Link to check consent against cannot be built.
 * @property ids Enumerate exactly these native objects, when known (e.g. a
 *   re-fetch). Empty means "enumerate by [window]/[predicates] instead."
 * @property window Restrict to objects observed in this range, when the
 *   source supports time-bounded enumeration.
 * @property limit Page size hint. A source may return fewer.
 * @property cursor Opaque continuation from a previous [PerceivePage.nextCursor].
 * @property predicates An implicit conjunction: a result must satisfy every
 *   entry. This is the whole query as the caller means it, whether or not the
 *   source can evaluate it — a source that can't returns the predicate in
 *   [PerceivePage.residual] rather than the caller leaving it out.
 */
data class PerceiveQuery(
    val linkId: LinkId,
    val ids: List<String> = emptyList(),
    val window: ClosedRange<Instant>? = null,
    val limit: Int? = null,
    val cursor: String? = null,
    val predicates: List<PerceivePredicate> = emptyList(),
)

/**
 * One page of a [PerceiveSource] enumeration.
 *
 * @property entities The successfully projected results. When [isExact] is
 *   false this is an over-approximation of the query — consume it through
 *   [applyResidual], not directly.
 * @property evaluated The query predicates the source applied natively.
 * @property residual The query predicates the source could not apply; the
 *   caller must still apply them. Together with [evaluated] this accounts for
 *   every [PerceiveQuery.predicates] entry exactly once. Neither list has a
 *   default: an implementation cannot build a page without stating what it
 *   evaluated, and the zero-effort honest answer is [unfiltered].
 * @property nextCursor Feed back into [PerceiveQuery.cursor] to continue.
 *   Null means this was the last page.
 * @property partialFailures Load-bearing: a source enumerating 500 photos
 *   where 3 have malformed metadata returns 497 [entities] plus three typed
 *   failures here, never a shortened list with this left empty. An empty
 *   list is a claim that nothing failed — a source must never make that
 *   claim by omission.
 */
data class PerceivePage<out T>(
    val entities: List<T>,
    val evaluated: List<PerceivePredicate>,
    val residual: List<PerceivePredicate>,
    val nextCursor: String? = null,
    val partialFailures: List<CanonConversionFailure> = emptyList(),
) {

    /**
     * True when [entities] answer the query exactly. Derived from [residual],
     * never set by hand, so "over-approximate" is a fact the page carries
     * rather than a flag a source can forget.
     */
    val isExact: Boolean get() = residual.isEmpty()

    companion object {

        /**
         * A page from a source that evaluated none of [query]'s predicates:
         * every one is returned as residual. Always a correct answer — the
         * caller's [applyResidual] does the filtering.
         */
        fun <T> unfiltered(
            query: PerceiveQuery,
            entities: List<T>,
            nextCursor: String? = null,
            partialFailures: List<CanonConversionFailure> = emptyList(),
        ): PerceivePage<T> = PerceivePage(
            entities = entities,
            evaluated = emptyList(),
            residual = query.predicates,
            nextCursor = nextCursor,
            partialFailures = partialFailures,
        )
    }
}
