package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp
import kotlin.math.sqrt

/**
 * Blends palettes and color ramps across the waveform surface based on
 * the dominant cognitive phase at each point.
 *
 * When two agents in different phases are near each other, the palette
 * blends at the boundary -- you can see EXECUTE's hot discharge meeting
 * EVALUATE's cool afterglow as a gradient in character density and color.
 *
 * @param influenceRadius Maximum distance (in world units) at which an agent
 *        affects the palette selection. Beyond this, returns null (use fallback).
 */
class PhaseBlender(
    val influenceRadius: Float = 10f
) {
    /**
     * Determine the effective palette and color ramp at a world position,
     * blending between nearby agents' phases weighted by proximity.
     *
     * Returns null if no agents are within influence radius (caller should
     * use a fallback palette).
     *
     * @param worldX X coordinate in world space
     * @param worldZ Z coordinate in world space
     * @param agents Current agent layer
     * @return Pair of (palette, colorRamp) for the dominant phase, or null
     */
    fun blendedPaletteAt(
        worldX: Float,
        worldZ: Float,
        agents: AgentLayer
    ): Pair<AsciiLuminancePalette, CognitiveColorRamp>? {
        val allAgents = agents.allAgents
        if (allAgents.isEmpty()) return null

        // Accumulate weighted phase influence
        val phaseWeights = mutableMapOf<CognitivePhase, Float>()
        var totalWeight = 0f

        for (agent in allAgents) {
            val dx = worldX - agent.position.x
            val dz = worldZ - agent.position.y
            val dist = sqrt(dx * dx + dz * dz)

            if (dist > influenceRadius) continue

            // Inverse-distance weighting with smooth falloff
            // Weight = (1 - dist/radius)^2 for smooth falloff at edges
            val normalizedDist = dist / influenceRadius
            val weight = (1f - normalizedDist) * (1f - normalizedDist)

            val phase = agent.cognitivePhase
            phaseWeights[phase] = (phaseWeights[phase] ?: 0f) + weight
            totalWeight += weight
        }

        if (totalWeight < 0.001f) return null

        // Find the dominant phase (highest total weight)
        val dominantPhase = phaseWeights.maxByOrNull { it.value }?.key
            ?: return null

        return paletteForPhase(dominantPhase) to CognitiveColorRamp.forPhase(dominantPhase)
    }

    companion object {
        /**
         * Get the luminance palette for a cognitive phase.
         */
        fun paletteForPhase(phase: CognitivePhase): AsciiLuminancePalette = when (phase) {
            CognitivePhase.PERCEIVE -> AsciiLuminancePalette.PERCEIVE
            CognitivePhase.RECALL -> AsciiLuminancePalette.RECALL
            CognitivePhase.PLAN -> AsciiLuminancePalette.PLAN
            CognitivePhase.EXECUTE -> AsciiLuminancePalette.EXECUTE
            CognitivePhase.EVALUATE -> AsciiLuminancePalette.EVALUATE
            CognitivePhase.LOOP -> AsciiLuminancePalette.STANDARD
            CognitivePhase.NONE -> AsciiLuminancePalette.STANDARD
        }
    }
}
