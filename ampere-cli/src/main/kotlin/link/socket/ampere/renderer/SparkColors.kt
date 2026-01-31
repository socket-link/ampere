package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity

/**
 * Consistent color theming based on cognitive affinity.
 *
 * Each affinity has a designated primary color:
 * - ANALYTICAL: Cyan - Precision, logic, coolness
 * - EXPLORATORY: Green - Growth, discovery, nature
 * - OPERATIONAL: Yellow - Action, energy, urgency
 * - INTEGRATIVE: Magenta - Synthesis, creativity, wholeness
 *
 * These colors are applied consistently across:
 * - Agent name in agent pane
 * - Affinity label in Spark stack
 * - Agent attribution in events
 * - Focus mode header/border
 */
object SparkColors {

    /**
     * Get the color for a cognitive affinity.
     */
    fun forAffinity(affinity: CognitiveAffinity): TextStyle = when (affinity) {
        CognitiveAffinity.ANALYTICAL -> TextColors.cyan
        CognitiveAffinity.EXPLORATORY -> TextColors.green
        CognitiveAffinity.OPERATIONAL -> TextColors.yellow
        CognitiveAffinity.INTEGRATIVE -> TextColors.magenta
    }

    /**
     * Get the color for a cognitive affinity by name.
     */
    fun forAffinityName(affinityName: String): TextStyle = when (affinityName.uppercase()) {
        "ANALYTICAL" -> TextColors.cyan
        "EXPLORATORY" -> TextColors.green
        "OPERATIONAL" -> TextColors.yellow
        "INTEGRATIVE" -> TextColors.magenta
        else -> TextColors.white
    }

    /**
     * Get a symbol for each affinity type.
     */
    fun symbolForAffinity(affinity: CognitiveAffinity): String = when (affinity) {
        CognitiveAffinity.ANALYTICAL -> "◆"
        CognitiveAffinity.EXPLORATORY -> "◇"
        CognitiveAffinity.OPERATIONAL -> "▶"
        CognitiveAffinity.INTEGRATIVE -> "●"
    }

    /**
     * Get the short description for each affinity.
     */
    fun shortDescription(affinity: CognitiveAffinity): String = when (affinity) {
        CognitiveAffinity.ANALYTICAL -> "precision-focused"
        CognitiveAffinity.EXPLORATORY -> "curiosity-driven"
        CognitiveAffinity.OPERATIONAL -> "action-oriented"
        CognitiveAffinity.INTEGRATIVE -> "holistic understanding"
    }

    /**
     * Icons for Spark events.
     */
    object SparkIcons {
        const val APPLIED = "⚡"      // Spark applied - context expansion
        const val REMOVED = "↩"      // Spark removed - context contraction
        const val SNAPSHOT = "🧠"    // Cognitive state snapshot
        const val STACK_BRANCH = "├─"
        const val STACK_LAST = "└─"
        const val STACK_ROOT = "◆"
    }

    /**
     * Render cognitive depth as a visual indicator.
     *
     * @param depth The current Spark stack depth
     * @param style The display style to use
     * @return A formatted string representing the depth
     */
    fun renderDepthIndicator(depth: Int, style: DepthDisplayStyle = DepthDisplayStyle.NUMERIC): String {
        return when (style) {
            DepthDisplayStyle.NUMERIC -> "depth: $depth"
            DepthDisplayStyle.BARS -> {
                val maxDepth = 5
                val filled = "█".repeat(depth.coerceAtMost(maxDepth))
                val empty = "░".repeat((maxDepth - depth).coerceAtLeast(0))
                val overflow = if (depth > maxDepth) "+" else ""
                "$filled$empty$overflow"
            }
            DepthDisplayStyle.DOTS -> {
                val maxDepth = 5
                val filled = "●".repeat(depth.coerceAtMost(maxDepth))
                val empty = "○".repeat((maxDepth - depth).coerceAtLeast(0))
                val overflow = if (depth > maxDepth) "+" else ""
                "$filled$empty$overflow"
            }
            DepthDisplayStyle.ARROWS -> {
                "▸".repeat(depth.coerceAtMost(7))
            }
        }
    }

    /**
     * Display styles for cognitive depth indicator.
     */
    enum class DepthDisplayStyle {
        NUMERIC,  // "depth: 3"
        BARS,     // "███░░"
        DOTS,     // "●●●○○"
        ARROWS    // "▸▸▸"
    }
}
