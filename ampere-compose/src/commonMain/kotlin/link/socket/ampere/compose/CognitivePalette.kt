package link.socket.ampere.compose

import androidx.compose.ui.graphics.Color
import link.socket.ampere.animation.agent.CognitivePhase

/**
 * Color palette for Compose rendering, equivalent to AmperePalette for terminal.
 *
 * Maps the same semantic color roles to Compose Color values.
 */
object CognitivePalette {

    // Substrate
    val substrateDim = Color(0xFF3A3F47)      // Dark blue-gray
    val substrateMid = Color(0xFF5F9EA0)      // Teal
    val substrateBright = Color(0xFF87CEEB)   // Cyan

    // Agents
    val agentIdle = Color(0xFF808080)         // Gray
    val agentActive = Color(0xFFFFD700)       // Gold
    val agentProcessing = Color(0xFFFF8C00)   // Orange
    val agentComplete = Color(0xFF32CD32)     // Green

    // Flow
    val flowDormant = Color(0xFF3A3F47)
    val flowActive = Color(0xFF9370DB)        // Purple
    val flowToken = Color(0xFFFFD700)         // Yellow

    // Accents
    val sparkAccent = Color(0xFFFF6B6B)       // Coral
    val logoBolt = Color(0xFFFFD700)          // Bright yellow
    val logoText = Color(0xFF00CED1)          // Cyan

    // Cognitive phase colors (new — distinct from agent state colors)
    val perceive = Color(0xFF6495ED)          // Cornflower blue (sensory)
    val recall = Color(0xFFDAA520)            // Goldenrod (memory warmth)
    val plan = Color(0xFF9370DB)              // Medium purple (exploration)
    val execute = Color(0xFFFF8C00)           // Dark orange (discharge)
    val evaluate = Color(0xFF66CDAA)          // Medium aquamarine (reflection)
    val loop = Color(0xFF708090)              // Slate gray (reset)

    /**
     * Get substrate color for a density value.
     */
    fun forDensity(density: Float): Color = when {
        density < 0.3f -> substrateDim
        density < 0.6f -> substrateMid
        else -> substrateBright
    }

    /**
     * Get color for a cognitive phase.
     */
    fun forPhase(phase: CognitivePhase): Color = when (phase) {
        CognitivePhase.PERCEIVE -> perceive
        CognitivePhase.RECALL -> recall
        CognitivePhase.PLAN -> plan
        CognitivePhase.EXECUTE -> execute
        CognitivePhase.EVALUATE -> evaluate
        CognitivePhase.LOOP -> loop
        CognitivePhase.NONE -> agentIdle
    }
}
