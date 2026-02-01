package link.socket.ampere.cli.animation.logo

import link.socket.ampere.cli.animation.substrate.Vector2

/**
 * A positioned glyph that forms part of the logo.
 *
 * @property position Position relative to logo center
 * @property glyph The character to display
 * @property visible Whether the glyph is currently visible
 * @property densityThreshold Density required to reveal this glyph
 */
data class GlyphPosition(
    val position: Vector2,
    val glyph: Char,
    var visible: Boolean = false,
    val densityThreshold: Float = 0.8f
)

/**
 * Defines the Ampere logo layout and provides scaling utilities.
 *
 * The logo has two variants:
 * - Full: Multi-line with decorative elements
 * - Minimal: Single-line "⚡ AMPERE"
 */
object Logo {

    /**
     * The full logo layout.
     *
     * ```
     *      ⚡
     *     ╱ ▲ ╲
     *    ╱ ╱ ╲ ╲
     *   ╱ ╱   ╲ ╲
     *  ━━━━━━━━━━━
     *    AMPERE
     * ```
     */
    private val FULL_LOGO = listOf(
        "     ⚡     ",
        "    ╱ ▲ ╲   ",
        "   ╱ ╱ ╲ ╲  ",
        "  ╱ ╱   ╲ ╲ ",
        " ━━━━━━━━━━ ",
        "   AMPERE   "
    )

    /**
     * Minimal single-line logo.
     */
    private const val MINIMAL_LOGO = "⚡ AMPERE"

    /**
     * Logo width in characters (full version).
     */
    val fullWidth: Int = FULL_LOGO.maxOfOrNull { it.length } ?: 0

    /**
     * Logo height in lines (full version).
     */
    val fullHeight: Int = FULL_LOGO.size

    /**
     * Minimal logo width.
     */
    val minimalWidth: Int = MINIMAL_LOGO.length

    /**
     * Get glyph positions for the full logo centered at the given position.
     *
     * @param centerX Center X coordinate
     * @param centerY Center Y coordinate
     * @return List of glyph positions
     */
    fun getFullLogoGlyphs(centerX: Float, centerY: Float): List<GlyphPosition> {
        val glyphs = mutableListOf<GlyphPosition>()

        val startX = centerX - fullWidth / 2f
        val startY = centerY - fullHeight / 2f

        FULL_LOGO.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, char ->
                if (char != ' ') {
                    glyphs.add(
                        GlyphPosition(
                            position = Vector2(startX + colIndex, startY + rowIndex),
                            glyph = char,
                            densityThreshold = getDensityThresholdForChar(char)
                        )
                    )
                }
            }
        }

        return glyphs
    }

    /**
     * Get glyph positions for the minimal logo centered at the given position.
     *
     * @param centerX Center X coordinate
     * @param centerY Center Y coordinate
     * @return List of glyph positions
     */
    fun getMinimalLogoGlyphs(centerX: Float, centerY: Float): List<GlyphPosition> {
        val glyphs = mutableListOf<GlyphPosition>()

        val startX = centerX - minimalWidth / 2f

        MINIMAL_LOGO.forEachIndexed { index, char ->
            if (char != ' ') {
                glyphs.add(
                    GlyphPosition(
                        position = Vector2(startX + index, centerY),
                        glyph = char,
                        densityThreshold = getDensityThresholdForChar(char)
                    )
                )
            }
        }

        return glyphs
    }

    /**
     * Choose logo variant based on available terminal space.
     *
     * @param width Available width
     * @param height Available height
     * @param centerX Center X coordinate
     * @param centerY Center Y coordinate
     * @return Appropriate logo glyphs
     */
    fun getLogoForSize(width: Int, height: Int, centerX: Float, centerY: Float): List<GlyphPosition> {
        return if (width >= fullWidth + 4 && height >= fullHeight + 2) {
            getFullLogoGlyphs(centerX, centerY)
        } else {
            getMinimalLogoGlyphs(centerX, centerY)
        }
    }

    /**
     * Get the raw logo lines for direct rendering.
     */
    fun getFullLogoLines(): List<String> = FULL_LOGO

    /**
     * Get the minimal logo string.
     */
    fun getMinimalLogoString(): String = MINIMAL_LOGO

    /**
     * Density threshold varies by character importance.
     * The bolt and text reveal first, decorative elements last.
     */
    private fun getDensityThresholdForChar(char: Char): Float = when (char) {
        '⚡' -> 0.6f  // Lightning bolt reveals early (hero element)
        in 'A'..'Z' -> 0.7f  // Text reveals next
        '▲' -> 0.75f  // Inner triangle
        '━' -> 0.8f   // Base line
        '╱', '╲' -> 0.85f  // Decorative slashes reveal last
        else -> 0.8f
    }

    /**
     * Get the center position (in logo coordinates) of the lightning bolt.
     */
    fun getBoltPosition(centerX: Float, centerY: Float): Vector2 {
        return Vector2(centerX, centerY - fullHeight / 2f)
    }

    /**
     * Get all attractors for the logo formation animation.
     * Returns positions with relative attraction strengths.
     */
    fun getAttractorPositions(centerX: Float, centerY: Float): List<Pair<Vector2, Float>> {
        val attractors = mutableListOf<Pair<Vector2, Float>>()

        // Primary attractor at bolt position (strongest)
        attractors.add(getBoltPosition(centerX, centerY) to 3f)

        // Center attractor
        attractors.add(Vector2(centerX, centerY) to 2f)

        // Text baseline attractor
        attractors.add(Vector2(centerX, centerY + fullHeight / 2f - 1) to 1.5f)

        return attractors
    }
}
