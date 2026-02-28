package link.socket.ampere.compose

import link.socket.phosphor.field.Particle
import link.socket.phosphor.field.ParticleType
import link.socket.phosphor.math.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticleCanvasRendererTest {

    // --- colorForParticle ---

    @Test
    fun colorForParticle_mote_returns_substrateMid() {
        val particle = makeParticle(ParticleType.MOTE)
        assertEquals(CognitivePalette.substrateMid, ParticleCanvasRenderer.colorForParticle(particle))
    }

    @Test
    fun colorForParticle_spark_returns_sparkAccent() {
        val particle = makeParticle(ParticleType.SPARK)
        assertEquals(CognitivePalette.sparkAccent, ParticleCanvasRenderer.colorForParticle(particle))
    }

    @Test
    fun colorForParticle_trail_returns_flowToken() {
        val particle = makeParticle(ParticleType.TRAIL)
        assertEquals(CognitivePalette.flowToken, ParticleCanvasRenderer.colorForParticle(particle))
    }

    @Test
    fun colorForParticle_ripple_returns_substrateBright() {
        val particle = makeParticle(ParticleType.RIPPLE)
        assertEquals(CognitivePalette.substrateBright, ParticleCanvasRenderer.colorForParticle(particle))
    }

    // --- particleRadius ---

    @Test
    fun particleRadius_zero_life_returns_quarter_cell() {
        // (0 * 4 + 1) * (20 / 4) = 1 * 5 = 5
        assertEquals(5f, ParticleCanvasRenderer.particleRadius(0f, 20f))
    }

    @Test
    fun particleRadius_full_life_returns_expected() {
        // (1 * 4 + 1) * (20 / 4) = 5 * 5 = 25
        assertEquals(25f, ParticleCanvasRenderer.particleRadius(1f, 20f))
    }

    @Test
    fun particleRadius_half_life_returns_expected() {
        // (0.5 * 4 + 1) * (20 / 4) = 3 * 5 = 15
        assertEquals(15f, ParticleCanvasRenderer.particleRadius(0.5f, 20f))
    }

    @Test
    fun particleRadius_proportional_to_cell_width() {
        val r1 = ParticleCanvasRenderer.particleRadius(0.5f, 10f)
        val r2 = ParticleCanvasRenderer.particleRadius(0.5f, 20f)
        assertEquals(r1 * 2, r2, "Radius should double when cellWidth doubles")
    }

    // --- glowAlpha ---

    @Test
    fun glowAlpha_full_life_returns_02() {
        assertEquals(0.2f, ParticleCanvasRenderer.glowAlpha(1f))
    }

    @Test
    fun glowAlpha_zero_life_returns_0() {
        assertEquals(0f, ParticleCanvasRenderer.glowAlpha(0f))
    }

    @Test
    fun glowAlpha_half_life_returns_01() {
        assertEquals(0.1f, ParticleCanvasRenderer.glowAlpha(0.5f))
    }

    // --- coreAlpha ---

    @Test
    fun coreAlpha_full_life_returns_08() {
        assertEquals(0.8f, ParticleCanvasRenderer.coreAlpha(1f))
    }

    @Test
    fun coreAlpha_zero_life_returns_0() {
        assertEquals(0f, ParticleCanvasRenderer.coreAlpha(0f))
    }

    @Test
    fun coreAlpha_always_four_times_glow() {
        for (life in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val glow = ParticleCanvasRenderer.glowAlpha(life)
            val core = ParticleCanvasRenderer.coreAlpha(life)
            assertTrue(
                kotlin.math.abs(core - glow * 4f) < 0.001f,
                "coreAlpha should be 4x glowAlpha at life=$life",
            )
        }
    }

    // --- helpers ---

    private fun makeParticle(type: ParticleType): Particle =
        Particle(
            position = Vector2(0f, 0f),
            velocity = Vector2(0f, 0f),
            life = 1f,
            type = type,
            glyph = '.',
        )
}
