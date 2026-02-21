package link.socket.ampere.animation.render

/**
 * A single cell in the ASCII render buffer — character + foreground color + optional background.
 * This is the atom of the visual display. The waveform renderer produces a grid of these;
 * the platform-specific renderer converts them to ANSI codes or Compose text.
 *
 * @param char The ASCII character to display
 * @param fgColor ANSI 256-color foreground code
 * @param bgColor ANSI 256-color background code, null = transparent/default
 * @param bold Whether to render bold
 */
data class AsciiCell(
    val char: Char = ' ',
    val fgColor: Int = 7,
    val bgColor: Int? = null,
    val bold: Boolean = false
) {
    companion object {
        val EMPTY = AsciiCell()

        /**
         * Build a cell from surface lighting parameters.
         *
         * @param luminance 0.0 (dark) to 1.0 (bright)
         * @param normalX Horizontal surface normal component (-1.0 to 1.0)
         * @param normalY Vertical surface normal component (-1.0 to 1.0)
         * @param palette Character palette for this phase
         * @param colorRamp Color ramp for this phase
         * @return A fully resolved AsciiCell
         */
        fun fromSurface(
            luminance: Float,
            normalX: Float,
            normalY: Float,
            palette: AsciiLuminancePalette,
            colorRamp: CognitiveColorRamp
        ): AsciiCell {
            val ch = palette.charForSurface(luminance, normalX, normalY)
            val fg = colorRamp.colorForLuminance(luminance)
            val isBold = luminance > 0.8f
            return AsciiCell(
                char = ch,
                fgColor = fg,
                bgColor = null,
                bold = isBold
            )
        }

        /**
         * Build a cell from surface lighting parameters with ordered dithering.
         *
         * Screen coordinates drive the Bayer matrix pattern, breaking up
         * banding in both character selection and color.
         *
         * @param luminance 0.0 (dark) to 1.0 (bright)
         * @param normalX Horizontal surface normal component (-1.0 to 1.0)
         * @param normalY Vertical surface normal component (-1.0 to 1.0)
         * @param screenX Horizontal screen coordinate
         * @param screenY Vertical screen coordinate
         * @param palette Character palette for this phase
         * @param colorRamp Color ramp for this phase
         * @return A fully resolved AsciiCell with dithered character and color
         */
        fun fromSurfaceDithered(
            luminance: Float,
            normalX: Float,
            normalY: Float,
            screenX: Int,
            screenY: Int,
            palette: AsciiLuminancePalette,
            colorRamp: CognitiveColorRamp
        ): AsciiCell {
            val ch = palette.charForSurfaceDithered(luminance, normalX, normalY, screenX, screenY)
            val fg = colorRamp.colorForLuminanceDithered(luminance, screenX, screenY)
            val isBold = luminance > 0.8f
            return AsciiCell(
                char = ch,
                fgColor = fg,
                bgColor = null,
                bold = isBold
            )
        }
    }
}
