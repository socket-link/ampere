package link.socket.ampere.animation.render

/**
 * Ordered dithering using a 4x4 Bayer matrix.
 *
 * Ordered dithering breaks up banding artifacts in the luminance-to-character
 * quantization. At each screen cell, the fractional part of the scaled luminance
 * is compared against a spatially-varying threshold from the Bayer matrix.
 * This decides whether to round up or down to the adjacent palette entry,
 * producing a stippled mix that simulates intermediate brightness levels.
 *
 * The 4x4 Bayer matrix is tileable and produces a visually pleasing,
 * non-repetitive pattern well suited to ASCII art scales.
 */
object BayerDither {

    /**
     * 4x4 Bayer threshold matrix, values 0-15.
     * Each entry represents the order in which that position "turns on"
     * as brightness increases.
     */
    private val BAYER_4X4 = intArrayOf(
         0,  8,  2, 10,
        12,  4, 14,  6,
         3, 11,  1,  9,
        15,  7, 13,  5
    )

    /**
     * Get the Bayer threshold for a screen position, normalized to [0, 1).
     *
     * Used for ordered dithering: compare the fractional part of a scaled
     * value against this threshold to decide whether to round up or down.
     *
     * @param screenX Horizontal screen coordinate
     * @param screenY Vertical screen coordinate
     * @return Threshold in [0, 15/16], 16 distinct evenly-spaced values
     */
    fun threshold(screenX: Int, screenY: Int): Float {
        val x = screenX and 3 // mod 4
        val y = screenY and 3
        return BAYER_4X4[y * 4 + x] / 16f
    }
}
