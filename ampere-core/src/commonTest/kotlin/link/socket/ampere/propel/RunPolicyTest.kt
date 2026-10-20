package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What [RunPolicy] and [ObservePolicy] accept and refuse at construction (AMPR-480, task 1).
 *
 * The defaults are the cheapest honest run, and the default `observe` is the one that reads
 * nothing: a policy built with no arguments must come out `Off`, or every existing consumer
 * would start paying for a store read it never asked for.
 */
class RunPolicyTest {

    @Test
    fun `the default policy observes nothing`() {
        val policy = RunPolicy()
        assertEquals(FieldScope.Off, policy.observe.field)
        assertEquals(ObservePolicy(), policy.observe)
    }

    @Test
    fun `the default bounds are what the ticket names`() {
        val observe = ObservePolicy()
        assertEquals(20, observe.beliefLimit)
        assertEquals(10, observe.commitmentLimit)
        assertEquals(20, observe.actionLimit)
        assertEquals(200, observe.clip)
    }

    @Test
    fun `a zero limit is allowed and a negative one is refused`() {
        ObservePolicy(beliefLimit = 0, commitmentLimit = 0, actionLimit = 0)
        assertFailsWith<IllegalArgumentException> { ObservePolicy(beliefLimit = -1) }
        assertFailsWith<IllegalArgumentException> { ObservePolicy(commitmentLimit = -1) }
        assertFailsWith<IllegalArgumentException> { ObservePolicy(actionLimit = -1) }
    }

    @Test
    fun `a clip has at least one character`() {
        ObservePolicy(clip = 1)
        assertFailsWith<IllegalArgumentException> { ObservePolicy(clip = 0) }
        assertFailsWith<IllegalArgumentException> { ObservePolicy(clip = -5) }
    }

    @Test
    fun `an observing policy rides on the run policy beside the rest`() {
        val policy = RunPolicy(cycles = 2, observe = ObservePolicy(field = FieldScope.Goal, beliefLimit = 5))
        assertEquals(FieldScope.Goal, policy.observe.field)
        assertEquals(5, policy.observe.beliefLimit)
        assertEquals(2, policy.cycles)
    }

    @Test
    fun `the run policy still refuses what it refused before`() {
        assertFailsWith<IllegalArgumentException> { RunPolicy(cycles = 0) }
        assertFailsWith<IllegalArgumentException> { RunPolicy(judgmentResultClip = 0) }
        assertFailsWith<IllegalArgumentException> { RunPolicy(maxStepsPerCycle = 0) }
    }
}
