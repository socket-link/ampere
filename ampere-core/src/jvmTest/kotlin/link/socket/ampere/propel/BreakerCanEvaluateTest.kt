package link.socket.ampere.propel

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import link.socket.ampere.field.Rule

/**
 * AMPR-512: `Breaker.canEvaluate` answers for every `Rule` case, and its answers are the
 * kinds `admit` can evaluate.
 *
 * JVM-only because the completeness check needs `sealedSubclasses`.
 */
class BreakerCanEvaluateTest {

    @Test
    fun `every Rule case has an answer`() {
        val sampled = SAMPLES.keys.mapTo(mutableSetOf()) { it::class }

        assertEquals(
            sealedLeaves(Rule::class).toSet(),
            sampled,
            "every Rule case is sampled here, so a new one is given an answer in this test as well as in canEvaluate",
        )
        SAMPLES.forEach { (rule, expected) ->
            assertEquals(expected, Breaker.canEvaluate(rule), "canEvaluate($rule)")
        }
    }

    @Test
    fun `a count over anything but steps cannot be evaluated`() {
        assertFalse(Breaker.canEvaluate(Rule.Quota.Count(limit = 3, of = "files")))
    }

    @Test
    fun `a window in a zone this platform cannot resolve cannot be evaluated`() {
        val window = Rule.Window(from = LocalTime(22, 0), until = LocalTime(7, 0), timezone = "Mars/Olympus")
        assertFalse(Breaker.canEvaluate(window), "a zone that cannot resolve would fail every step it guards")
    }

    private fun sealedLeaves(root: KClass<*>): List<KClass<*>> =
        if (root.isSealed) root.sealedSubclasses.flatMap(::sealedLeaves) else listOf(root)

    private companion object {
        val DUE: Instant = Instant.parse("2026-10-30T12:00:00Z")

        /** One rule of each case, and whether the Breaker can evaluate it. */
        val SAMPLES: Map<Rule, Boolean> = mapOf(
            Rule.AccessPolicy(required = setOf("NetworkDomain(example.com)")) to true,
            Rule.Quota.Tokens(limit = 500) to true,
            Rule.Quota.Time(limit = 10.minutes) to true,
            Rule.Quota.Count(limit = 3, of = RunQuotas.STEPS) to true,
            Rule.Transition(allowed = mapOf("open" to setOf("done"))) to false,
            Rule.Requirements() to false,
            Rule.Dependency(dependsOn = emptySet()) to false,
            Rule.Deadline(due = DUE) to false,
            Rule.Cadence(every = 10.minutes) to false,
            Rule.Window(from = LocalTime(22, 0), until = LocalTime(7, 0), timezone = "America/Chicago") to true,
        )
    }
}
