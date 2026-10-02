package link.socket.ampere.probe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.probe.safety.HazardClassifier
import link.socket.ampere.probe.safety.KeywordHazardClassifier
import link.socket.ampere.probe.safety.SafetyProbe

/**
 * The Probes Ampere itself ships, in one place so a host that builds a
 * [ProbeRegistry] for an Oscilloscope listing registers them all with one
 * call. Consumers add their own Probes to the same registry afterwards.
 *
 * [freshnessMaxAge] is the [FreshnessProbe] tolerance. It is a parameter here
 * because max-age is consumer policy, never a fact field; the default exists
 * only so a listing can be built without choosing one.
 *
 * [hazardClassifier] is the [SafetyProbe]'s classifier. It defaults to the
 * deterministic 0W one, which is the only classifier a listing can safely
 * assume: a metered classifier is a cost the host must opt into by passing it.
 *
 * Returns the receiver so it composes:
 * `ProbeRegistry().registerAmpereProbes().also { it.register(myProbe) }`.
 */
fun ProbeRegistry.registerAmpereProbes(
    freshnessMaxAge: Duration = DEFAULT_FRESHNESS_MAX_AGE,
    now: () -> Instant = Clock.System::now,
    hazardClassifier: HazardClassifier = KeywordHazardClassifier,
): ProbeRegistry = apply {
    register(SequenceProbe())
    register(FreshnessProbe(maxAge = freshnessMaxAge, now = now))
    register(SafetyProbe(classifier = hazardClassifier))
}

/** Tolerance used by [registerAmpereProbes] when the host does not pass one. */
val DEFAULT_FRESHNESS_MAX_AGE: Duration = 24.hours
