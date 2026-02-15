package link.socket.ampere.animation.agent

import link.socket.ampere.animation.substrate.Vector2

/**
 * Cognitive phases from the PROPEL loop.
 * Each phase maps to a distinct visual choreography.
 */
enum class CognitivePhase {
    /** Gathering sensory input — particles drift inward */
    PERCEIVE,
    /** Memory activation — warm embers brightening */
    RECALL,
    /** Strategy formation — tentative structures testing formations */
    PLAN,
    /** Committed action — discharge/acceleration */
    EXECUTE,
    /** Reflection — afterglow, particles slow and persist */
    EVALUATE,
    /** Cycle complete — brief stillness before next iteration */
    LOOP,
    /** No active cognition */
    NONE
}

/**
 * Activity states for agents in the visualization.
 */
enum class AgentActivityState {
    /** Agent is coalescing from particles */
    SPAWNING,
    /** Agent is hollow/inactive (◎) */
    IDLE,
    /** Agent is filled/active (◉) */
    ACTIVE,
    /** Agent is processing with shimmer (◉ animated) */
    PROCESSING,
    /** Agent completed work (◎ with checkmark) */
    COMPLETE
}

/**
 * Visual representation of an agent in the TUI.
 *
 * Agents are the protagonists of the visualization - they illuminate when
 * active, dim when idle, and are connected by visible substrate flows.
 *
 * @property id Unique agent identifier
 * @property name Display name for the agent
 * @property role Agent's role (e.g., "reasoning", "codegen")
 * @property position Position in 2D space
 * @property state Current activity state
 * @property statusText Status message displayed below agent
 * @property pulsePhase Phase for shimmer animation (0.0-1.0)
 */
data class AgentVisualState(
    val id: String,
    val name: String,
    val role: String,
    val position: Vector2,
    val state: AgentActivityState = AgentActivityState.IDLE,
    val statusText: String = "",
    val pulsePhase: Float = 0f,
    val cognitivePhase: CognitivePhase = CognitivePhase.NONE,
    val phaseProgress: Float = 0f
) {
    /**
     * Create a copy with updated position.
     */
    fun withPosition(newPosition: Vector2): AgentVisualState = copy(position = newPosition)

    /**
     * Create a copy with updated state.
     */
    fun withState(newState: AgentActivityState): AgentVisualState = copy(state = newState)

    /**
     * Create a copy with updated status text.
     */
    fun withStatus(newStatus: String): AgentVisualState = copy(statusText = newStatus)

    /**
     * Create a copy with updated pulse phase.
     */
    fun withPulsePhase(phase: Float): AgentVisualState = copy(pulsePhase = phase % 1f)

    /**
     * Create a copy with updated cognitive phase and progress.
     */
    fun withCognitivePhase(phase: CognitivePhase, progress: Float = 0f): AgentVisualState =
        copy(cognitivePhase = phase, phaseProgress = progress.coerceIn(0f, 1f))

    /**
     * Get the primary glyph for this agent's current state.
     */
    fun getPrimaryGlyph(useUnicode: Boolean = true): Char {
        return AgentGlyphs.forState(state, useUnicode)
    }

    /**
     * Get the accent suffix (e.g., checkmark for COMPLETE).
     */
    fun getAccentSuffix(useUnicode: Boolean = true): String {
        return when (state) {
            AgentActivityState.COMPLETE -> if (useUnicode) " \u2713" else " [ok]"
            else -> ""
        }
    }
}

/**
 * Glyph definitions for agent visualization.
 */
object AgentGlyphs {
    // Unicode glyphs
    const val SPAWNING_UNICODE = '\u2591' // ░ (light shade)
    const val IDLE_UNICODE = '\u25CE'     // ◎ (bullseye)
    const val ACTIVE_UNICODE = '\u25C9'   // ◉ (fisheye)
    const val PROCESSING_UNICODE = '\u25C9' // ◉ (animated with shimmer)
    const val COMPLETE_UNICODE = '\u25CE' // ◎

    // ASCII fallbacks
    const val SPAWNING_ASCII = '#'
    const val IDLE_ASCII = 'o'
    const val ACTIVE_ASCII = '@'
    const val PROCESSING_ASCII = '@'
    const val COMPLETE_ASCII = 'o'

    // Spawning gradient glyphs for animation
    val SPAWNING_GRADIENT_UNICODE = charArrayOf('\u2591', '\u2592', '\u2593') // ░ ▒ ▓
    val SPAWNING_GRADIENT_ASCII = charArrayOf('.', 'o', '#')

    /**
     * Get the glyph for a given state.
     */
    fun forState(state: AgentActivityState, useUnicode: Boolean = true): Char {
        return if (useUnicode) {
            when (state) {
                AgentActivityState.SPAWNING -> SPAWNING_UNICODE
                AgentActivityState.IDLE -> IDLE_UNICODE
                AgentActivityState.ACTIVE -> ACTIVE_UNICODE
                AgentActivityState.PROCESSING -> PROCESSING_UNICODE
                AgentActivityState.COMPLETE -> COMPLETE_UNICODE
            }
        } else {
            when (state) {
                AgentActivityState.SPAWNING -> SPAWNING_ASCII
                AgentActivityState.IDLE -> IDLE_ASCII
                AgentActivityState.ACTIVE -> ACTIVE_ASCII
                AgentActivityState.PROCESSING -> PROCESSING_ASCII
                AgentActivityState.COMPLETE -> COMPLETE_ASCII
            }
        }
    }

    /**
     * Get spawning gradient glyph based on progress (0.0-1.0).
     */
    fun spawningGlyph(progress: Float, useUnicode: Boolean = true): Char {
        val glyphs = if (useUnicode) SPAWNING_GRADIENT_UNICODE else SPAWNING_GRADIENT_ASCII
        val index = (progress * (glyphs.size - 1)).toInt().coerceIn(0, glyphs.lastIndex)
        return glyphs[index]
    }
}

/**
 * Color scheme for agent rendering.
 */
object AgentColors {
    // ANSI 256-color codes
    const val IDLE = "\u001B[38;5;240m"      // Gray
    const val ACTIVE = "\u001B[38;5;226m"    // Gold/Yellow
    const val PROCESSING = "\u001B[38;5;226m" // Gold with shimmer
    const val SPAWNING = "\u001B[38;5;240m"  // Gray
    const val COMPLETE = "\u001B[38;5;82m"   // Green
    const val RESET = "\u001B[0m"

    // Role-based colors
    const val REASONING = "\u001B[38;5;213m"  // Pink/Magenta (Spark)
    const val CODEGEN = "\u001B[38;5;45m"     // Cyan (Jazz)
    const val COORDINATOR = "\u001B[38;5;226m" // Gold

    /**
     * Get color for agent state.
     */
    fun forState(state: AgentActivityState): String {
        return when (state) {
            AgentActivityState.SPAWNING -> SPAWNING
            AgentActivityState.IDLE -> IDLE
            AgentActivityState.ACTIVE -> ACTIVE
            AgentActivityState.PROCESSING -> PROCESSING
            AgentActivityState.COMPLETE -> COMPLETE
        }
    }

    /**
     * Get color for agent role.
     */
    fun forRole(role: String): String {
        return when (role.lowercase()) {
            "reasoning", "spark" -> REASONING
            "codegen", "code", "jazz" -> CODEGEN
            "coordinator" -> COORDINATOR
            else -> IDLE
        }
    }
}
