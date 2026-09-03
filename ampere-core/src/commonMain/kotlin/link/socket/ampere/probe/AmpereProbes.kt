package link.socket.ampere.probe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The Probes Ampere itself ships, in one place so a host that builds a
 * [ProbeRegistry] for an Oscilloscope listing registers them all with one
 * call. Consumers add their own Probes to the same registry afterwards.
 *
 * [freshnessMaxAge] is the [FreshnessProbe] tolerance. It is a parameter here
 * because max-age is consumer policy, never a fact field; the default exists
 * only so a listing can be built without choosing one.
 *
 * Returns the receiver so it composes:
 * `ProbeRegistry().registerAmpereProbes().also { it.register(myProbe) }`.
 */
fun ProbeRegistry.registerAmpereProbes(
    freshnessMaxAge: Duration = DEFAULT_FRESHNESS_MAX_AGE,
    now: () -> Instant = Clock.System::now,
): ProbeRegistry = apply {
    register(SequenceProbe())
    register(FreshnessProbe(maxAge = freshnessMaxAge, now = now))
}

/** Tolerance used by [registerAmpereProbes] when the host does not pass one. */
val DEFAULT_FRESHNESS_MAX_AGE: Duration = 24.hours
