package link.socket.ampere.cli.animation.flow

import link.socket.ampere.cli.animation.particle.Particle
import link.socket.ampere.cli.animation.particle.ParticleType
import link.socket.ampere.cli.animation.substrate.Vector2

/**
 * State of a flow connection between agents.
 */
enum class FlowState {
    /** Connection exists but inactive */
    DORMANT,
    /** Handoff initiating */
    ACTIVATING,
    /** Task in transit */
    TRANSMITTING,
    /** Handoff complete */
    RECEIVED
}

/**
 * A task token traveling along a flow connection.
 *
 * The token is the visual representation of work being delegated
 * between agents. It travels along a path and leaves trail particles.
 *
 * @property position Current position along the path
 * @property glyph Character to display (default: ●)
 * @property trailParticles Particles left behind the token
 */
data class TaskToken(
    val position: Vector2,
    val glyph: Char = '●',
    val trailParticles: List<Particle> = emptyList()
) {
    companion object {
        /** Unicode glyphs for task tokens */
        object Glyphs {
            const val DEFAULT = '●'
            const val ACTIVE = '◉'
            const val COMPLETED = '○'
            const val DELEGATING = '◈'

            /** Trail glyphs that fade based on age */
            val TRAIL_GRADIENT = listOf('●', '◎', '○', '·', ' ')

            fun forProgress(progress: Float): Char = when {
                progress < 0.1f -> DELEGATING
                progress < 0.9f -> ACTIVE
                else -> COMPLETED
            }
        }

        /** Colors for task tokens */
        object Colors {
            const val ACTIVE = "\u001B[38;5;226m"   // Yellow
            const val TRAIL = "\u001B[38;5;240m"    // Gray
            const val RECEIVED = "\u001B[38;5;46m"  // Green
            const val RESET = "\u001B[0m"
        }
    }

    /**
     * Create a trail particle at the current position.
     */
    fun createTrailParticle(): Particle {
        return Particle(
            position = position,
            velocity = Vector2.ZERO,
            life = 0.8f,
            type = ParticleType.TRAIL,
            glyph = Glyphs.TRAIL_GRADIENT[0]
        )
    }

    /**
     * Update trail particles, aging them and removing dead ones.
     */
    fun withUpdatedTrail(deltaTime: Float, decayRate: Float = 0.5f): TaskToken {
        val updatedTrail = trailParticles.mapNotNull { particle ->
            val newLife = particle.life - (deltaTime * decayRate)
            if (newLife <= 0f) {
                null
            } else {
                // Update glyph based on remaining life
                val glyphIndex = ((1f - newLife) * (Glyphs.TRAIL_GRADIENT.size - 1)).toInt()
                    .coerceIn(0, Glyphs.TRAIL_GRADIENT.size - 1)
                particle.copy(
                    life = newLife,
                    glyph = Glyphs.TRAIL_GRADIENT[glyphIndex]
                )
            }
        }
        return copy(trailParticles = updatedTrail)
    }

    /**
     * Add a new trail particle at current position.
     */
    fun withNewTrailParticle(): TaskToken {
        val newParticle = createTrailParticle()
        return copy(trailParticles = trailParticles + newParticle)
    }
}

/**
 * A connection between two agents that can transmit task tokens.
 *
 * FlowConnection represents the visual "pipe" between agents.
 * When a handoff occurs, a TaskToken travels along this connection.
 *
 * @property id Unique identifier for this connection
 * @property sourceAgentId ID of the source agent
 * @property targetAgentId ID of the target agent
 * @property state Current state of the connection
 * @property progress Animation progress (0.0 to 1.0) for transmitting state
 * @property taskToken The token currently in transit (if any)
 * @property path Calculated path points between agents
 */
data class FlowConnection(
    val id: String,
    val sourceAgentId: String,
    val targetAgentId: String,
    val state: FlowState = FlowState.DORMANT,
    val progress: Float = 0f,
    val taskToken: TaskToken? = null,
    val path: List<Vector2> = emptyList()
) {
    /** Whether this connection is currently active */
    val isActive: Boolean get() = state != FlowState.DORMANT

    /** Whether a token is currently transmitting */
    val isTransmitting: Boolean get() = state == FlowState.TRANSMITTING && taskToken != null

    /**
     * Get the current token position based on progress along the path.
     */
    fun getCurrentPathPosition(): Vector2? {
        if (path.isEmpty()) return null
        val index = (progress * (path.size - 1)).toInt().coerceIn(0, path.size - 1)
        return path[index]
    }

    /**
     * Create a copy with updated state.
     */
    fun withState(newState: FlowState): FlowConnection = copy(state = newState)

    /**
     * Create a copy with updated progress.
     */
    fun withProgress(newProgress: Float): FlowConnection = copy(progress = newProgress.coerceIn(0f, 1f))

    /**
     * Create a copy with a task token.
     */
    fun withToken(token: TaskToken): FlowConnection = copy(taskToken = token)

    /**
     * Create a copy with calculated path.
     */
    fun withPath(newPath: List<Vector2>): FlowConnection = copy(path = newPath)

    /**
     * Start a handoff animation.
     */
    fun startHandoff(sourcPosition: Vector2): FlowConnection {
        val token = TaskToken(
            position = sourcPosition,
            glyph = TaskToken.Companion.Glyphs.DELEGATING
        )
        return copy(
            state = FlowState.ACTIVATING,
            progress = 0f,
            taskToken = token
        )
    }

    /**
     * Complete the handoff.
     */
    fun completeHandoff(): FlowConnection {
        return copy(
            state = FlowState.RECEIVED,
            progress = 1f,
            taskToken = taskToken?.copy(glyph = TaskToken.Companion.Glyphs.COMPLETED)
        )
    }

    /**
     * Reset to dormant state.
     */
    fun reset(): FlowConnection {
        return copy(
            state = FlowState.DORMANT,
            progress = 0f,
            taskToken = null
        )
    }
}
