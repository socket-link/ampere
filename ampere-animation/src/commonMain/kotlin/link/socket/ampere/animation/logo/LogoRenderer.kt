package link.socket.ampere.animation.logo

import link.socket.ampere.animation.particle.ParticleRenderer
import link.socket.ampere.animation.particle.ParticleSystem
import kotlin.math.roundToInt

/**
 * Renders the logo crystallization animation to a character grid.
 *
 * @property width Grid width
 * @property height Grid height
 * @property useUnicode Whether to use Unicode characters
 */
class LogoRenderer(
    private val width: Int,
    private val height: Int,
    private val useUnicode: Boolean = true
) {
    private val particleRenderer = ParticleRenderer(width, height, useUnicode)

    /**
     * Render the crystallization state to a character grid.
     *
     * @param crystallizer The crystallization state to render
     * @param background Background character
     * @return List of rows as strings
     */
    fun render(
        crystallizer: LogoCrystallizer,
        background: Char = ' '
    ): List<String> {
        // Create base grid with particles
        val grid = Array(height) { CharArray(width) { background } }

        // Render particles first (they go behind glyphs)
        crystallizer.let { c ->
            // Get particle positions from the system
            val particleGrid = particleRenderer.render(
                getParticleSystem(c),
                background
            )

            // Copy particle grid to our grid
            for (y in 0 until minOf(height, particleGrid.size)) {
                val row = particleGrid[y]
                for (x in 0 until minOf(width, row.length)) {
                    if (row[x] != background) {
                        grid[y][x] = row[x]
                    }
                }
            }
        }

        // Render visible glyphs on top
        crystallizer.getVisibleGlyphs().forEach { glyph ->
            val x = glyph.position.x.roundToInt()
            val y = glyph.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                grid[y][x] = glyph.glyph
            }
        }

        return grid.map { it.concatToString() }
    }

    /**
     * Render with ANSI colors.
     *
     * @param crystallizer The crystallization state
     * @param particles The particle system
     * @param background Background character
     * @return List of colored rows
     */
    fun renderColored(
        crystallizer: LogoCrystallizer,
        particles: ParticleSystem,
        background: Char = ' '
    ): List<String> {
        val grid = Array(height) { Array<Pair<Char, String?>>(width) { background to null } }

        // Render particles with color
        val coloredParticles = particleRenderer.renderColored(particles, background)
        for (y in 0 until minOf(height, coloredParticles.size)) {
            // Parse the colored row back to individual characters
            // This is simplified - in practice we'd track colors differently
            val row = coloredParticles[y]
            // For now, just use the basic particle colors
        }

        // Render particles without color first
        particles.getParticles().forEach { p ->
            val x = p.position.x.roundToInt()
            val y = p.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                val color = when {
                    p.life > 0.7f -> PARTICLE_COLOR_BRIGHT
                    p.life > 0.4f -> PARTICLE_COLOR_MEDIUM
                    else -> PARTICLE_COLOR_DIM
                }
                grid[y][x] = p.glyph to color
            }
        }

        // Render visible glyphs with logo color
        crystallizer.getVisibleGlyphs().forEach { glyph ->
            val x = glyph.position.x.roundToInt()
            val y = glyph.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                val color = getGlyphColor(glyph.glyph, crystallizer.phase)
                grid[y][x] = glyph.glyph to color
            }
        }

        return grid.map { row ->
            buildString {
                row.forEach { (char, color) ->
                    if (color != null) {
                        append(color)
                        append(char)
                        append(RESET)
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

    /**
     * Render just the final logo (without particles).
     */
    fun renderLogo(
        centerX: Float,
        centerY: Float,
        background: Char = ' '
    ): List<String> {
        val grid = Array(height) { CharArray(width) { background } }

        val glyphs = Logo.getLogoForSize(width, height, centerX, centerY)
        glyphs.forEach { glyph ->
            val x = glyph.position.x.roundToInt()
            val y = glyph.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                grid[y][x] = glyph.glyph
            }
        }

        return grid.map { it.concatToString() }
    }

    private fun getGlyphColor(glyph: Char, phase: CrystallizationPhase): String {
        return when {
            // Lightning bolt is always bright yellow
            glyph == '⚡' -> BOLT_COLOR

            // During formation, newly revealed glyphs flash
            phase == CrystallizationPhase.FORM -> LOGO_COLOR_FORMING

            // After settle, full brightness
            phase == CrystallizationPhase.SETTLE ||
                phase == CrystallizationPhase.COMPLETE -> LOGO_COLOR_STABLE

            // Text is cyan
            glyph in 'A'..'Z' -> TEXT_COLOR

            // Decorative elements are dimmer
            else -> DECORATION_COLOR
        }
    }

    // Helper to get particle system - would need to be passed or stored
    private fun getParticleSystem(crystallizer: LogoCrystallizer): ParticleSystem {
        // This is a workaround - in real usage, particles would be accessible
        return ParticleSystem()
    }

    companion object {
        // ANSI color codes
        private const val RESET = "\u001B[0m"
        private const val BOLT_COLOR = "\u001B[38;5;226m"          // Bright yellow
        private const val LOGO_COLOR_FORMING = "\u001B[38;5;231m"  // Bright white
        private const val LOGO_COLOR_STABLE = "\u001B[38;5;45m"    // Cyan
        private const val TEXT_COLOR = "\u001B[38;5;39m"           // Light blue
        private const val DECORATION_COLOR = "\u001B[38;5;244m"    // Gray

        private const val PARTICLE_COLOR_BRIGHT = "\u001B[38;5;226m"  // Yellow
        private const val PARTICLE_COLOR_MEDIUM = "\u001B[38;5;220m"  // Orange-yellow
        private const val PARTICLE_COLOR_DIM = "\u001B[38;5;240m"     // Gray
    }
}
