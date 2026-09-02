package link.socket.ampere.canon

import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A bounded recurrence: repeat every [every], stopping after [count]
 * occurrences or at [until].
 *
 * **Deliberately not RFC 5545.** An `RRULE` carries calendar vocabulary —
 * `BYDAY`, `BYSETPOS`, `WKST`, the whole expansion algebra — that no canon
 * provider round-trips honestly, so admitting it would put a grammar in canon
 * that every binding then approximates. [every] is a wall-clock
 * `kotlin.time.Duration`: stdlib, serializable, already in `ampere-core`
 * commonMain, and expressible by every provider that ships recurrence at all.
 *
 * Sub-daily [every] values are legal here — the *intent* is what canon
 * records — even where a binding cannot express them. EventKit's frequency
 * enum bottoms out at daily; that gap is named in the Apple binding's lossy
 * notes rather than silently rejected at construction.
 *
 * **Four-consumer note.** [every] is a span and survives a game or simulation
 * clock unchanged; [until] is a wall-clock instant and does not. A
 * [count]-bounded recurrence is therefore the portable form. This inherits
 * Ampere's existing `Instant`-everywhere choice; it is named here, not solved
 * here.
 *
 * **Why the bound is a factory and not a `require` in `init`** — the same
 * reason as [CanonProse]. Rejecting an out-of-range value at *decode* time
 * would make an already-recorded trace permanently undecodable, which is
 * precisely the failure the `@SerialName` stability invariant exists to
 * prevent. A canon type must always decode; bounding is a write-side concern.
 *
 * @property every The wall-clock span between occurrences.
 * @property count The number of occurrences, when the recurrence is
 *   occurrence-bounded.
 * @property until The wall-clock instant after which it stops, when the
 *   recurrence is date-bounded.
 */
@ConsistentCopyVisibility
@Serializable
data class CanonRecurrence private constructor(
    val every: Duration,
    val count: Int? = null,
    val until: Instant? = null,
) {

    /** Whether this value honours the [of] rules. */
    val isWithinBounds: Boolean
        get() = every > Duration.ZERO && (count == null || count > 0) && (count != null || until != null)

    companion object {

        /**
         * The only construction path. Fails when [every] is not positive, when
         * [count] is present and not positive, or when the recurrence is
         * unbounded — an open-ended repeat is a schedule no consumer can
         * finish reasoning about, and neither Apple provider produces one
         * Ampere is obliged to model.
         */
        fun of(
            every: Duration,
            count: Int? = null,
            until: Instant? = null,
        ): Result<CanonRecurrence> = when {
            every <= Duration.ZERO ->
                Result.failure(IllegalArgumentException("recurrence interval must be positive, was $every"))

            count != null && count <= 0 ->
                Result.failure(IllegalArgumentException("recurrence count must be positive, was $count"))

            count == null && until == null ->
                Result.failure(IllegalArgumentException("recurrence must be bounded by a count or an until instant"))

            else -> Result.success(CanonRecurrence(every = every, count = count, until = until))
        }
    }
}
