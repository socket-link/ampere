package link.socket.ampere.animation.render

import kotlin.math.abs

/**
 * Maps normalized luminance (0.0 = dark, 1.0 = bright) to ASCII characters.
 *
 * The palette is the retinal cone — it determines what "brightness" looks like
 * in character space. Different palettes create different visual textures,
 * like how rod cells vs cone cells create different perceptions from the same light.
 *
 * @param characters Ordered from darkest to brightest
 * @param name Human-readable palette name
 */
data class AsciiLuminancePalette(
    val characters: String,
    val name: String
) {
    init {
        require(characters.isNotEmpty()) { "Palette must have at least one character" }
    }

    private val lastIndex = characters.lastIndex

    /**
     * Select a character for the given luminance.
     * @param luminance 0.0 (dark) to 1.0 (bright), clamped internally
     */
    fun charForLuminance(luminance: Float): Char {
        val clamped = luminance.coerceIn(0f, 1f)
        val index = (clamped * lastIndex).toInt().coerceIn(0, lastIndex)
        return characters[index]
    }

    /**
     * Select a character for the given luminance with ordered dithering.
     *
     * Applies a Bayer matrix threshold offset to break up banding between
     * adjacent palette characters. The dither strength is one character step,
     * so smooth gradients produce a stippled mix of neighboring characters.
     *
     * @param luminance 0.0 (dark) to 1.0 (bright), clamped internally
     * @param screenX Horizontal screen coordinate (for dither pattern)
     * @param screenY Vertical screen coordinate (for dither pattern)
     */
    fun charForLuminanceDithered(luminance: Float, screenX: Int, screenY: Int): Char {
        if (lastIndex == 0) return characters[0]
        val clamped = luminance.coerceIn(0f, 1f)
        val scaled = clamped * lastIndex
        val base = scaled.toInt().coerceIn(0, lastIndex)
        if (base >= lastIndex) return characters[lastIndex]
        val frac = scaled - base
        val index = if (frac > BayerDither.threshold(screenX, screenY)) base + 1 else base
        return characters[index]
    }

    /**
     * Select a character considering surface normal direction.
     * Surfaces facing left use '\', facing right use '/', horizontal use '-', vertical use '|'.
     * Falls back to luminance-only selection for non-edge cases.
     *
     * @param luminance 0.0 to 1.0
     * @param normalX Horizontal component of surface normal (-1.0 to 1.0)
     * @param normalY Vertical component of surface normal (-1.0 to 1.0)
     */
    fun charForSurface(luminance: Float, normalX: Float, normalY: Float): Char {
        val clamped = luminance.coerceIn(0f, 1f)

        // Only use directional characters for mid-luminance edges
        if (clamped in 0.15f..0.85f) {
            val absX = abs(normalX)
            val absY = abs(normalY)

            // Strong directional bias triggers edge characters
            if (absX > EDGE_THRESHOLD || absY > EDGE_THRESHOLD) {
                return when {
                    absX > absY && normalX > 0f -> '/'
                    absX > absY && normalX < 0f -> '\\'
                    absY > absX && absY > EDGE_THRESHOLD -> '|'
                    absX > EDGE_THRESHOLD && absY > EDGE_THRESHOLD -> {
                        if (normalX > 0f) '/' else '\\'
                    }
                    else -> '-'
                }
            }
        }

        return charForLuminance(clamped)
    }

    /**
     * Select a character considering surface normal direction, with ordered dithering.
     *
     * Edge characters (/, \, |, -) are not dithered — only luminance-based
     * characters receive the dither pattern.
     *
     * @param luminance 0.0 to 1.0
     * @param normalX Horizontal component of surface normal (-1.0 to 1.0)
     * @param normalY Vertical component of surface normal (-1.0 to 1.0)
     * @param screenX Horizontal screen coordinate (for dither pattern)
     * @param screenY Vertical screen coordinate (for dither pattern)
     */
    fun charForSurfaceDithered(
        luminance: Float,
        normalX: Float,
        normalY: Float,
        screenX: Int,
        screenY: Int
    ): Char {
        val clamped = luminance.coerceIn(0f, 1f)

        // Only use directional characters for mid-luminance edges
        if (clamped in 0.15f..0.85f) {
            val absX = abs(normalX)
            val absY = abs(normalY)

            // Strong directional bias triggers edge characters
            if (absX > EDGE_THRESHOLD || absY > EDGE_THRESHOLD) {
                return when {
                    absX > absY && normalX > 0f -> '/'
                    absX > absY && normalX < 0f -> '\\'
                    absY > absX && absY > EDGE_THRESHOLD -> '|'
                    absX > EDGE_THRESHOLD && absY > EDGE_THRESHOLD -> {
                        if (normalX > 0f) '/' else '\\'
                    }
                    else -> '-'
                }
            }
        }

        return charForLuminanceDithered(clamped, screenX, screenY)
    }

    companion object {
        private const val EDGE_THRESHOLD = 0.5f

        /** Standard grayscale ramp — the classic donut.c palette */
        val STANDARD = AsciiLuminancePalette(
            characters = " .,-~:;=!*#\$@\u2588",
            name = "standard"
        )

        /** Sparse, ethereal — for PERCEIVE phase (sensory input arriving) */
        val PERCEIVE = AsciiLuminancePalette(
            characters = " \u00B7\u2219.\u00B7:\u2218\u25CB\u25CC\u25EF",
            name = "perceive"
        )

        /** Warm, clustered — for RECALL phase (memory activation) */
        val RECALL = AsciiLuminancePalette(
            characters = " .\u00B7*\u2726\u2B21\u25C6\u25CF",
            name = "recall"
        )

        /** Structured, branching — for PLAN phase (competing futures) */
        val PLAN = AsciiLuminancePalette(
            characters = " \u2591\u2592\u2593\u2502\u251C\u2524\u252C\u2534\u253C",
            name = "plan"
        )

        /** Dense, aggressive — for EXECUTE phase (discharge) */
        val EXECUTE = AsciiLuminancePalette(
            characters = " .:;+=*#%@\u2588\u26A1",
            name = "execute"
        )

        /** Diffuse, reflective — for EVALUATE phase (afterglow, fading) */
        val EVALUATE = AsciiLuminancePalette(
            characters = " .\u00B7:*\u2218\u25CB\u25CC ",
            name = "evaluate"
        )
    }
}
