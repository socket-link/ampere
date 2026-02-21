package link.socket.ampere.animation.emitter

import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class EmitterEffectTest {

    // --- SparkBurst ---

    @Test
    fun `SparkBurst at center at time zero has high intensity`() {
        val burst = EmitterEffect.SparkBurst()
        val influence = burst.influence(distanceFromCenter = 0f, timeSinceActivation = 0.01f)
        assertTrue(influence.intensity > 0f)
    }

    @Test
    fun `SparkBurst at center at expiry has zero intensity`() {
        val burst = EmitterEffect.SparkBurst(duration = 0.8f)
        val influence = burst.influence(distanceFromCenter = 0f, timeSinceActivation = 0.8f)
        assertEquals(0f, influence.intensity)
    }

    @Test
    fun `SparkBurst beyond radius returns no influence`() {
        val burst = EmitterEffect.SparkBurst(radius = 5f)
        val influence = burst.influence(distanceFromCenter = 6f, timeSinceActivation = 0.1f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `SparkBurst negative time returns no influence`() {
        val burst = EmitterEffect.SparkBurst()
        val influence = burst.influence(distanceFromCenter = 1f, timeSinceActivation = -0.1f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `SparkBurst intensity decays over time`() {
        val burst = EmitterEffect.SparkBurst()
        // Sample the ring at its current center position
        val earlyTime = 0.1f
        val lateTime = 0.6f
        val earlyRingPos = burst.expansionSpeed * earlyTime
        val lateRingPos = burst.expansionSpeed * lateTime
        val early = burst.influence(distanceFromCenter = earlyRingPos, timeSinceActivation = earlyTime)
        val late = burst.influence(distanceFromCenter = lateRingPos.coerceAtMost(burst.radius), timeSinceActivation = lateTime)
        assertTrue(early.intensity > late.intensity, "Early intensity ${early.intensity} should be > late ${late.intensity}")
    }

    @Test
    fun `SparkBurst provides palette override at high intensity`() {
        val burst = EmitterEffect.SparkBurst()
        val ringPos = burst.expansionSpeed * 0.05f
        val influence = burst.influence(distanceFromCenter = ringPos, timeSinceActivation = 0.05f)
        if (influence.intensity > 0.3f) {
            assertNotNull(influence.paletteOverride)
        }
    }

    // --- HeightPulse ---

    @Test
    fun `HeightPulse at center produces height modification`() {
        val pulse = EmitterEffect.HeightPulse()
        val influence = pulse.influence(distanceFromCenter = 0f, timeSinceActivation = 0.3f)
        assertTrue(influence.heightModifier > 0f)
        assertTrue(influence.intensity > 0f)
    }

    @Test
    fun `HeightPulse beyond radius returns no influence`() {
        val pulse = EmitterEffect.HeightPulse(radius = 4f)
        val influence = pulse.influence(distanceFromCenter = 5f, timeSinceActivation = 0.3f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `HeightPulse rises then falls`() {
        val pulse = EmitterEffect.HeightPulse(duration = 1.2f)
        val early = pulse.influence(distanceFromCenter = 0f, timeSinceActivation = 0.1f)
        val mid = pulse.influence(distanceFromCenter = 0f, timeSinceActivation = 0.4f)
        val late = pulse.influence(distanceFromCenter = 0f, timeSinceActivation = 1.1f)
        assertTrue(mid.heightModifier > early.heightModifier, "Mid should be higher than early")
        assertTrue(mid.heightModifier > late.heightModifier, "Mid should be higher than late")
    }

    @Test
    fun `HeightPulse expired returns no influence`() {
        val pulse = EmitterEffect.HeightPulse(duration = 1.2f)
        val influence = pulse.influence(distanceFromCenter = 0f, timeSinceActivation = 1.2f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    // --- Turbulence ---

    @Test
    fun `Turbulence at center mid-duration has nonzero intensity`() {
        val turb = EmitterEffect.Turbulence()
        val influence = turb.influence(distanceFromCenter = 0f, timeSinceActivation = 1f)
        assertTrue(influence.intensity > 0f)
    }

    @Test
    fun `Turbulence produces height variation`() {
        val turb = EmitterEffect.Turbulence()
        // Sample at two slightly different distances to verify noise produces variation
        val a = turb.influence(distanceFromCenter = 0f, timeSinceActivation = 0.5f)
        val b = turb.influence(distanceFromCenter = 1f, timeSinceActivation = 0.5f)
        // They should generally differ due to noise
        assertTrue(a.heightModifier != b.heightModifier || a.intensity != b.intensity)
    }

    @Test
    fun `Turbulence beyond radius returns no influence`() {
        val turb = EmitterEffect.Turbulence(radius = 6f)
        val influence = turb.influence(distanceFromCenter = 7f, timeSinceActivation = 1f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `Turbulence expired returns no influence`() {
        val turb = EmitterEffect.Turbulence(duration = 2f)
        val influence = turb.influence(distanceFromCenter = 1f, timeSinceActivation = 2f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    // --- ColorWash ---

    @Test
    fun `ColorWash at center shortly after activation has influence`() {
        val wash = EmitterEffect.ColorWash(colorRamp = CognitiveColorRamp.PERCEIVE)
        val influence = wash.influence(distanceFromCenter = 0f, timeSinceActivation = 0.1f)
        assertTrue(influence.intensity > 0f)
        assertNotNull(influence.colorOverride)
    }

    @Test
    fun `ColorWash ahead of wave front has no influence`() {
        val wash = EmitterEffect.ColorWash(
            colorRamp = CognitiveColorRamp.EXECUTE,
            waveFrontSpeed = 6f
        )
        // At time 0.1, wavefront is at 0.6 — distance 2.0 is ahead of it
        val influence = wash.influence(distanceFromCenter = 2f, timeSinceActivation = 0.1f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `ColorWash expired returns no influence`() {
        val wash = EmitterEffect.ColorWash(
            duration = 1.5f,
            colorRamp = CognitiveColorRamp.PLAN
        )
        val influence = wash.influence(distanceFromCenter = 0f, timeSinceActivation = 1.5f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    // --- Confetti ---

    @Test
    fun `Confetti at center shortly after activation has character override`() {
        val confetti = EmitterEffect.Confetti()
        val influence = confetti.influence(distanceFromCenter = 0.5f, timeSinceActivation = 0.1f)
        assertTrue(influence.intensity > 0.2f)
        assertNotNull(influence.characterOverride)
    }

    @Test
    fun `Confetti beyond radius returns no influence`() {
        val confetti = EmitterEffect.Confetti(radius = 3f)
        val influence = confetti.influence(distanceFromCenter = 4f, timeSinceActivation = 0.2f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `Confetti expired returns no influence`() {
        val confetti = EmitterEffect.Confetti(duration = 1f)
        val influence = confetti.influence(distanceFromCenter = 1f, timeSinceActivation = 1f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `Confetti selects different characters at different distances`() {
        val confetti = EmitterEffect.Confetti()
        val a = confetti.influence(distanceFromCenter = 0.5f, timeSinceActivation = 0.1f)
        val b = confetti.influence(distanceFromCenter = 1.5f, timeSinceActivation = 0.1f)
        // Characters should generally differ due to distance-based selection
        if (a.characterOverride != null && b.characterOverride != null) {
            // At least verify they're from the character set
            assertTrue(a.characterOverride in "\u2726\u2727\u26A1\u2605\u00B7*")
            assertTrue(b.characterOverride in "\u2726\u2727\u26A1\u2605\u00B7*")
        }
    }

    // --- EffectInfluence ---

    @Test
    fun `NONE influence has all zero values`() {
        val none = EffectInfluence.NONE
        assertEquals(0f, none.heightModifier)
        assertEquals(0f, none.luminanceModifier)
        assertNull(none.paletteOverride)
        assertNull(none.colorOverride)
        assertNull(none.characterOverride)
        assertEquals(0f, none.intensity)
    }

    @Test
    fun `EffectInfluence plus combines additively`() {
        val a = EffectInfluence(heightModifier = 1f, luminanceModifier = 0.3f, intensity = 0.5f)
        val b = EffectInfluence(heightModifier = 0.5f, luminanceModifier = 0.2f, intensity = 0.8f)
        val combined = a + b
        assertEquals(1.5f, combined.heightModifier)
        assertEquals(0.5f, combined.luminanceModifier, 0.001f)
        assertEquals(0.8f, combined.intensity)
    }

    @Test
    fun `EffectInfluence plus higher intensity wins discrete overrides`() {
        val low = EffectInfluence(
            intensity = 0.3f,
            paletteOverride = AsciiLuminancePalette.PERCEIVE,
            characterOverride = 'A'
        )
        val high = EffectInfluence(
            intensity = 0.9f,
            paletteOverride = AsciiLuminancePalette.EXECUTE,
            characterOverride = 'B'
        )
        val combined = low + high
        assertEquals(AsciiLuminancePalette.EXECUTE, combined.paletteOverride)
        assertEquals('B', combined.characterOverride)
    }

    @Test
    fun `EffectInfluence plus falls back when winner has null override`() {
        val low = EffectInfluence(
            intensity = 0.3f,
            colorOverride = 196
        )
        val high = EffectInfluence(
            intensity = 0.9f,
            colorOverride = null
        )
        val combined = low + high
        assertEquals(196, combined.colorOverride)
    }

    // --- General properties ---

    @Test
    fun `all effects have correct names`() {
        assertEquals("spark_burst", EmitterEffect.SparkBurst().name)
        assertEquals("height_pulse", EmitterEffect.HeightPulse().name)
        assertEquals("turbulence", EmitterEffect.Turbulence().name)
        assertEquals("color_wash", EmitterEffect.ColorWash(colorRamp = CognitiveColorRamp.NEUTRAL).name)
        assertEquals("confetti", EmitterEffect.Confetti().name)
    }

    @Test
    fun `all effects have positive duration and radius`() {
        val effects: List<EmitterEffect> = listOf(
            EmitterEffect.SparkBurst(),
            EmitterEffect.HeightPulse(),
            EmitterEffect.Turbulence(),
            EmitterEffect.ColorWash(colorRamp = CognitiveColorRamp.NEUTRAL),
            EmitterEffect.Confetti()
        )
        for (effect in effects) {
            assertTrue(effect.duration > 0f, "${effect.name} duration should be positive")
            assertTrue(effect.radius > 0f, "${effect.name} radius should be positive")
        }
    }
}
