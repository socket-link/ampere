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

    // ========================================================================
    // Cadence (AMPR-483, task 1)
    // ========================================================================

    @Test
    fun `the default cadence perceives and recalls once and learns at close`() {
        val cadence = RunPolicy().cadence
        assertEquals(PerceiveCadence.OncePerRun, cadence.perceive)
        assertEquals(RecallCadence.OncePerRun, cadence.recall)
        assertEquals(LearnCadence.AtClose, cadence.learn)
    }

    @Test
    fun `WhenDrifted takes a probability strictly between 0 and 1`() {
        assertEquals(0.5, PerceiveCadence.WhenDrifted(0.5).moveAbove)
        PerceiveCadence.WhenDrifted(0.01)
        PerceiveCadence.WhenDrifted(0.99)
        assertFailsWith<IllegalArgumentException> { PerceiveCadence.WhenDrifted(0.0) }
        assertFailsWith<IllegalArgumentException> { PerceiveCadence.WhenDrifted(1.0) }
        assertFailsWith<IllegalArgumentException> { PerceiveCadence.WhenDrifted(-0.2) }
        assertFailsWith<IllegalArgumentException> { PerceiveCadence.WhenDrifted(1.5) }
        assertFailsWith<IllegalArgumentException> { PerceiveCadence.WhenDrifted(Double.NaN) }
    }

    @Test
    fun `the acting cadences ride on the run policy beside the rest`() {
        val drifted = RunPolicy(
            cycles = 3,
            observe = ObservePolicy(field = FieldScope.Goal),
            cadence = PhaseCadence(perceive = PerceiveCadence.WhenDrifted(0.6), recall = RecallCadence.WithPerceive),
        )
        assertEquals(PerceiveCadence.WhenDrifted(0.6), drifted.cadence.perceive)
        assertEquals(RecallCadence.WithPerceive, drifted.cadence.recall)

        val every = RunPolicy(cadence = PhaseCadence(perceive = PerceiveCadence.EveryCycle))
        assertEquals(PerceiveCadence.EveryCycle, every.cadence.perceive)
        assertEquals(RecallCadence.OncePerRun, every.cadence.recall)
    }
}
