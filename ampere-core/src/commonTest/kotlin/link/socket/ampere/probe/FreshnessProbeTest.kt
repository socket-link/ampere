package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/**
 * AMPR-323 task 2 validation: the three outcomes against a pinned clock, and
 * the contravariance path — a plain `object : Observed` that is not a canon
 * type runs through the same Probe as a [CanonProvenance].
 */
class FreshnessProbeTest {

    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private val maxAge = 1.hours
    private val probe = FreshnessProbe(maxAge = maxAge, now = { now })

    /** A consumer-side binding: not a canon type, knows nothing but its timestamp. */
    private fun observedAt(instant: Instant): Observed = object : Observed {
        override val observedAt = instant
    }

    @Test
    fun `fresh observation holds`() = runTest {
        val verdict = probe.evaluate(observedAt(now - 30.minutes))

        assertEquals(Verdict.Holds(reason = "observed 30m ago"), verdict)
    }

    @Test
    fun `observation exactly at max age still holds`() = runTest {
        val verdict = probe.evaluate(observedAt(now - maxAge))

        assertEquals(Verdict.Holds(reason = "observed 1h ago"), verdict)
    }

    @Test
    fun `one second past max age is undetermined with cause STALE not violated`() = runTest {
        val verdict = probe.evaluate(observedAt(now - maxAge - 1.seconds))

        assertEquals(
            Verdict.Undetermined(reason = "observed 1h 0m 1s ago, max 1h", cause = UndeterminedCause.STALE),
            verdict,
        )
    }

    @Test
    fun `observedAt in the future warns about clock skew`() = runTest {
        val verdict = probe.evaluate(observedAt(now + 5.minutes))

        assertEquals(Verdict.Warn(reason = "observedAt is 5m ahead of now"), verdict)
    }

    @Test
    fun `canon provenance is an Observed subject`() = runTest {
        val provenance = CanonProvenance(
            sourceHandle = SourceHandle(linkId = LinkId("link-1"), sourceSystem = "apple.mail", nativeId = "native-1"),
            observedAt = now - 2.hours,
        )

        val verdict = probe.evaluate(provenance)

        assertIs<Verdict.Undetermined>(verdict)
        assertEquals(UndeterminedCause.STALE, verdict.cause)
    }

    @Test
    fun `a suite over Observed accepts the freshness probe beside a wider one`() = runTest {
        val anyProbe = object : Probe<Any> {
            override val id = ProbeId("any")

            override suspend fun evaluate(subject: Any): Verdict = Verdict.Holds()
        }
        val suite = ProbeSuite<Observed>(listOf(probe, anyProbe))

        val reports = suite.evaluate(subjectId = "line-1", subject = observedAt(now - 1.minutes))

        assertEquals(listOf(ProbeId("ampere.freshness"), ProbeId("any")), reports.map { it.probeId })
        assertEquals(Verdict.Holds(reason = "observed 1m ago"), reports[0].verdict)
    }

    @Test
    fun `ampere wiring lists the freshness probe beside the sequence probe`() {
        val registry = ProbeRegistry().registerAmpereProbes(freshnessMaxAge = maxAge, now = { now })

        assertEquals(listOf(ProbeId(SequenceProbe.ID), ProbeId(FreshnessProbe.ID)), registry.all().map { it.id })
        assertIs<FreshnessProbe>(registry.get(ProbeId("ampere.freshness")))
    }
}
