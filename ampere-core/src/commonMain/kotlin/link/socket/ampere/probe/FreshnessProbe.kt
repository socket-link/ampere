package link.socket.ampere.probe

import kotlin.time.Duration
import kotlinx.datetime.Instant

/**
 * Freshness guarantee: is a Recalled fact recent enough to be used as
 * evidence? A spec fetched in March is not evidence in June.
 *
 * Max-age is per-Probe, not per-fact: the fact carries the observation
 * ([Observed.observedAt]); the consumer carries the policy ([maxAge]). This is
 * the same split the eval SPI makes between a `Reading` and its `Tolerance`.
 *
 * Outcomes:
 * - age within [maxAge] → [Verdict.Holds].
 * - age beyond [maxAge] → [Verdict.Undetermined] with
 *   [UndeterminedCause.STALE], not [Verdict.Violated]. A stale fact does not
 *   prove the constraint false; it means the evidence cannot be used to
 *   acquit. Convict-but-not-acquit.
 * - `observedAt` ahead of [now] (clock skew) → [Verdict.Warn]. The fact is not
 *   stale, but a timestamp from the future says one of the two clocks is
 *   wrong, and the age it implies cannot be trusted.
 *
 * @property maxAge Oldest observation this Probe will accept as evidence.
 * @property now Clock, injected so tests can pin it. Production passes
 *   `Clock.System::now`.
 */
class FreshnessProbe(
    private val maxAge: Duration,
    private val now: () -> Instant,
    override val id: ProbeId = ProbeId(ID),
) : Probe<Observed> {

    companion object {
        const val ID = "ampere.freshness"
    }

    override suspend fun evaluate(subject: Observed): Verdict {
        val age = now() - subject.observedAt
        return when {
            age.isNegative() -> Verdict.Warn(reason = "observedAt is ${-age} ahead of now")
            age <= maxAge -> Verdict.Holds(reason = "observed $age ago")
            else -> Verdict.Undetermined(
                reason = "observed $age ago, max $maxAge",
                cause = UndeterminedCause.STALE,
            )
        }
    }
}
