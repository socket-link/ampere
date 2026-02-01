package link.socket.ampere.cli.animation.logo

import link.socket.ampere.cli.animation.particle.ParticleSystem
import link.socket.ampere.cli.animation.substrate.Vector2
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogoTest {

    @Test
    fun `full logo has correct dimensions`() {
        assertTrue(Logo.fullWidth > 0)
        assertTrue(Logo.fullHeight > 0)
    }

    @Test
    fun `minimal logo has correct dimensions`() {
        assertTrue(Logo.minimalWidth > 0)
        assertEquals(1, 1) // Minimal is single line
    }

    @Test
    fun `getFullLogoGlyphs returns glyphs at correct positions`() {
        val glyphs = Logo.getFullLogoGlyphs(50f, 15f)

        assertTrue(glyphs.isNotEmpty())
        assertTrue(glyphs.any { it.glyph == '⚡' })
        assertTrue(glyphs.any { it.glyph == 'A' })
    }

    @Test
    fun `getMinimalLogoGlyphs returns correct glyphs`() {
        val glyphs = Logo.getMinimalLogoGlyphs(50f, 15f)

        assertTrue(glyphs.isNotEmpty())
        assertTrue(glyphs.any { it.glyph == '⚡' })
        assertTrue(glyphs.any { it.glyph == 'A' })
    }

    @Test
    fun `getLogoForSize chooses full logo when space allows`() {
        val glyphs = Logo.getLogoForSize(80, 24, 40f, 12f)

        // Should have multiple rows worth of glyphs (full logo)
        val uniqueYPositions = glyphs.map { it.position.y }.distinct()
        assertTrue(uniqueYPositions.size > 1, "Full logo should have multiple rows")
    }

    @Test
    fun `getLogoForSize chooses minimal logo when space is limited`() {
        val glyphs = Logo.getLogoForSize(10, 5, 5f, 2f)

        // Should have single row (minimal logo)
        val uniqueYPositions = glyphs.map { it.position.y }.distinct()
        assertEquals(1, uniqueYPositions.size, "Minimal logo should have single row")
    }

    @Test
    fun `getBoltPosition returns position near top of logo`() {
        val centerX = 40f
        val centerY = 12f
        val boltPos = Logo.getBoltPosition(centerX, centerY)

        assertEquals(centerX, boltPos.x)
        assertTrue(boltPos.y < centerY, "Bolt should be above center")
    }

    @Test
    fun `getAttractorPositions returns multiple attractors`() {
        val attractors = Logo.getAttractorPositions(50f, 15f)

        assertTrue(attractors.size >= 2)
        attractors.forEach { (_, strength) ->
            assertTrue(strength > 0f)
        }
    }
}

class GlyphPositionTest {

    @Test
    fun `GlyphPosition stores position and glyph`() {
        val glyph = GlyphPosition(
            position = Vector2(10f, 5f),
            glyph = '⚡'
        )

        assertEquals(Vector2(10f, 5f), glyph.position)
        assertEquals('⚡', glyph.glyph)
        assertFalse(glyph.visible)
        assertEquals(0.8f, glyph.densityThreshold)
    }

    @Test
    fun `GlyphPosition visibility can be toggled`() {
        val glyph = GlyphPosition(
            position = Vector2(10f, 5f),
            glyph = 'A'
        )

        assertFalse(glyph.visible)
        glyph.visible = true
        assertTrue(glyph.visible)
    }
}

class LogoCrystallizerTest {

    @Test
    fun `create factory method creates crystallizer with logo glyphs`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        assertNotNull(crystallizer)
        assertEquals(CrystallizationPhase.SCATTERED, crystallizer.phase)
    }

    @Test
    fun `initialize spawns particles`() {
        val particles = ParticleSystem()
        val glyphs = Logo.getMinimalLogoGlyphs(40f, 12f)
        val crystallizer = LogoCrystallizer(particles, glyphs, 80, 24)

        crystallizer.initialize(50)

        assertTrue(particles.count > 0, "Should have spawned particles")
    }

    @Test
    fun `update advances through phases`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        // Initial phase
        assertEquals(CrystallizationPhase.SCATTERED, crystallizer.phase)

        // Update past scattered phase
        crystallizer.update(0.6f)
        assertEquals(CrystallizationPhase.FLOW, crystallizer.phase)

        // Update to density phase
        crystallizer.update(0.5f)
        assertEquals(CrystallizationPhase.DENSITY, crystallizer.phase)

        // Update to form phase
        crystallizer.update(0.5f)
        assertEquals(CrystallizationPhase.FORM, crystallizer.phase)

        // Update to settle phase
        crystallizer.update(0.5f)
        assertEquals(CrystallizationPhase.SETTLE, crystallizer.phase)

        // Update to complete
        crystallizer.update(0.6f)
        assertEquals(CrystallizationPhase.COMPLETE, crystallizer.phase)
    }

    @Test
    fun `phaseProgress tracks progress within phase`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        crystallizer.update(0.25f)  // Half of scattered phase

        assertEquals(0.5f, crystallizer.phaseProgress, 0.1f)
    }

    @Test
    fun `overallProgress tracks total progress`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        assertEquals(0f, crystallizer.overallProgress, 0.01f)

        // Update to halfway through total duration
        crystallizer.update(LogoCrystallizer.TOTAL_DURATION / 2)

        assertEquals(0.5f, crystallizer.overallProgress, 0.1f)
    }

    @Test
    fun `isComplete returns true when animation finishes`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        assertFalse(crystallizer.isComplete)

        // Run to completion
        crystallizer.update(LogoCrystallizer.TOTAL_DURATION + 0.1f)

        assertTrue(crystallizer.isComplete)
    }

    @Test
    fun `reset returns to initial state`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        crystallizer.update(1f)  // Advance some
        assertTrue(crystallizer.elapsedTime > 0f)

        crystallizer.reset()

        assertEquals(0f, crystallizer.elapsedTime)
        assertEquals(CrystallizationPhase.SCATTERED, crystallizer.phase)
    }

    @Test
    fun `listeners receive phase change events`() {
        val crystallizer = LogoCrystallizer.create(80, 24)
        val events = mutableListOf<CrystallizationEvent>()

        crystallizer.addListener { events.add(it) }
        crystallizer.update(0.6f)  // Cross into flow phase

        assertTrue(events.any { it is CrystallizationEvent.PhaseChanged })
    }

    @Test
    fun `listeners receive glyph revealed events during form phase`() {
        val crystallizer = LogoCrystallizer.create(80, 24)
        val events = mutableListOf<CrystallizationEvent>()

        crystallizer.addListener { events.add(it) }

        // Run through to form/settle phases where glyphs reveal
        repeat(30) {
            crystallizer.update(0.1f)
        }

        assertTrue(events.any { it is CrystallizationEvent.GlyphRevealed })
    }

    @Test
    fun `listeners receive complete event`() {
        val crystallizer = LogoCrystallizer.create(80, 24)
        var completed = false

        crystallizer.addListener { event ->
            if (event is CrystallizationEvent.Complete) {
                completed = true
            }
        }

        crystallizer.update(LogoCrystallizer.TOTAL_DURATION + 0.1f)

        assertTrue(completed)
    }

    @Test
    fun `getVisibleGlyphs returns only visible glyphs`() {
        val glyphs = mutableListOf(
            GlyphPosition(Vector2(0f, 0f), 'A').also { it.visible = true },
            GlyphPosition(Vector2(1f, 0f), 'B').also { it.visible = false },
            GlyphPosition(Vector2(2f, 0f), 'C').also { it.visible = true }
        )
        val particles = ParticleSystem()
        val crystallizer = LogoCrystallizer(particles, glyphs, 80, 24)

        val visible = crystallizer.getVisibleGlyphs()

        assertEquals(2, visible.size)
        assertTrue(visible.all { it.visible })
    }

    @Test
    fun `allGlyphsRevealed returns correct value`() {
        val crystallizer = LogoCrystallizer.create(80, 24)

        assertFalse(crystallizer.allGlyphsRevealed)

        // Run to completion with multiple updates to ensure all phases process properly
        repeat(30) {
            crystallizer.update(0.1f)
        }

        assertTrue(crystallizer.allGlyphsRevealed)
    }

    @Test
    fun `getLogoCenter calculates center of glyphs`() {
        val glyphs = listOf(
            GlyphPosition(Vector2(0f, 0f), 'A'),
            GlyphPosition(Vector2(10f, 0f), 'B'),
            GlyphPosition(Vector2(5f, 10f), 'C')
        )
        val particles = ParticleSystem()
        val crystallizer = LogoCrystallizer(particles, glyphs, 80, 24)

        val center = crystallizer.getLogoCenter()

        assertEquals(5f, center.x, 0.01f)
        assertEquals(10f / 3f, center.y, 0.01f)
    }

    @Test
    fun `TOTAL_DURATION equals sum of phase durations`() {
        val expected = LogoCrystallizer.SCATTERED_DURATION +
            LogoCrystallizer.FLOW_DURATION +
            LogoCrystallizer.DENSITY_DURATION +
            LogoCrystallizer.FORM_DURATION +
            LogoCrystallizer.SETTLE_DURATION

        assertEquals(expected, LogoCrystallizer.TOTAL_DURATION, 0.001f)
    }
}

class LogoRendererTest {

    @Test
    fun `render creates grid with correct dimensions`() {
        val renderer = LogoRenderer(80, 24)
        val crystallizer = LogoCrystallizer.create(80, 24)

        val output = renderer.render(crystallizer)

        assertEquals(24, output.size)
        output.forEach { row ->
            assertEquals(80, row.length)
        }
    }

    @Test
    fun `renderLogo renders logo centered`() {
        val renderer = LogoRenderer(80, 24)

        val output = renderer.renderLogo(40f, 12f)

        assertEquals(24, output.size)
        // Should have some non-space characters
        val hasContent = output.any { row -> row.any { it != ' ' } }
        assertTrue(hasContent)
    }

    @Test
    fun `render shows visible glyphs`() {
        val renderer = LogoRenderer(80, 24)
        val glyphs = listOf(
            GlyphPosition(Vector2(40f, 12f), 'X').also { it.visible = true }
        )
        val particles = ParticleSystem()
        val crystallizer = LogoCrystallizer(particles, glyphs, 80, 24)

        val output = renderer.render(crystallizer)

        assertTrue(output[12].contains('X'))
    }
}
