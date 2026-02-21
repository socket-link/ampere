package link.socket.ampere.animation.render

import link.socket.ampere.animation.agent.CognitivePhase

/**
 * Maps luminance to ANSI 256-color codes, with phase-specific hue shifts.
 *
 * Color is the emotional register of the visualization. The shape tells you
 * WHERE cognition is happening; the color tells you WHAT KIND of thinking it is.
 *
 * @param phase The cognitive phase this ramp represents
 * @param colorStops ANSI 256-color codes from dark to bright
 */
data class CognitiveColorRamp(
    val phase: CognitivePhase,
    val colorStops: List<Int>
) {
    init {
        require(colorStops.size >= 2) { "Color ramp must have at least 2 stops" }
    }

    private val lastIndex = colorStops.lastIndex

    /**
     * Get the ANSI 256-color code for a given luminance.
     * @param luminance 0.0 (dark) to 1.0 (bright), clamped internally
     * @return ANSI 256-color code
     */
    fun colorForLuminance(luminance: Float): Int {
        val clamped = luminance.coerceIn(0f, 1f)
        val index = (clamped * lastIndex).toInt().coerceIn(0, lastIndex)
        return colorStops[index]
    }

    /**
     * Get the ANSI 256-color code with ordered dithering.
     *
     * Applies Bayer matrix dithering to break up color banding between
     * adjacent color stops.
     *
     * @param luminance 0.0 (dark) to 1.0 (bright), clamped internally
     * @param screenX Horizontal screen coordinate (for dither pattern)
     * @param screenY Vertical screen coordinate (for dither pattern)
     * @return ANSI 256-color code
     */
    fun colorForLuminanceDithered(luminance: Float, screenX: Int, screenY: Int): Int {
        val clamped = luminance.coerceIn(0f, 1f)
        val scaled = clamped * lastIndex
        val base = scaled.toInt().coerceIn(0, lastIndex)
        if (base >= lastIndex) return colorStops[lastIndex]
        val frac = scaled - base
        val index = if (frac > BayerDither.threshold(screenX, screenY)) base + 1 else base
        return colorStops[index]
    }

    companion object {
        /** Cool blues -> white (sensory, exploratory) */
        val PERCEIVE = CognitiveColorRamp(
            phase = CognitivePhase.PERCEIVE,
            colorStops = listOf(17, 18, 24, 31, 38, 74, 110, 117, 153, 189, 231)
        )

        /** Dark amber -> warm gold (memory, warmth) */
        val RECALL = CognitiveColorRamp(
            phase = CognitivePhase.RECALL,
            colorStops = listOf(52, 94, 130, 136, 172, 178, 214, 220, 221)
        )

        /** Teal -> cyan (structured, deliberate) */
        val PLAN = CognitiveColorRamp(
            phase = CognitivePhase.PLAN,
            colorStops = listOf(23, 29, 30, 36, 37, 43, 79, 115, 159)
        )

        /** Red -> yellow -> white (discharge, energy) */
        val EXECUTE = CognitiveColorRamp(
            phase = CognitivePhase.EXECUTE,
            colorStops = listOf(52, 88, 124, 160, 196, 202, 208, 214, 220, 226, 231)
        )

        /** Purple -> dim lavender (reflection, settling) */
        val EVALUATE = CognitiveColorRamp(
            phase = CognitivePhase.EVALUATE,
            colorStops = listOf(53, 54, 91, 97, 134, 140, 141, 183, 189)
        )

        /** Neutral gray ramp for LOOP/NONE phases */
        val NEUTRAL = CognitiveColorRamp(
            phase = CognitivePhase.NONE,
            colorStops = listOf(232, 236, 240, 244, 248, 252, 255)
        )

        /**
         * Get the color ramp for a cognitive phase.
         */
        fun forPhase(phase: CognitivePhase): CognitiveColorRamp = when (phase) {
            CognitivePhase.PERCEIVE -> PERCEIVE
            CognitivePhase.RECALL -> RECALL
            CognitivePhase.PLAN -> PLAN
            CognitivePhase.EXECUTE -> EXECUTE
            CognitivePhase.EVALUATE -> EVALUATE
            CognitivePhase.LOOP -> NEUTRAL
            CognitivePhase.NONE -> NEUTRAL
        }
    }
}
