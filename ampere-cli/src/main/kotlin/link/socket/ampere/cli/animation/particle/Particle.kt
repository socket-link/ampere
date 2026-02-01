package link.socket.ampere.cli.animation.particle

import link.socket.ampere.cli.animation.substrate.Vector2

/**
 * Particle types for different visual effects.
 */
enum class ParticleType {
    /** Background ambient particles (· ∘ ◦) */
    MOTE,
    /** Initialization sparks (✦ ✧ ⋆) */
    SPARK,
    /** Following task tokens */
    TRAIL,
    /** Expanding from events */
    RIPPLE
}

/**
 * Optional attractor behavior for particles.
 */
data class ParticleAttractor(
    val target: Vector2,
    val strength: Float
)

/**
 * A single particle in the particle system.
 *
 * Particles have position, velocity, and a life value that decreases over time.
 * When life reaches 0, the particle should be despawned.
 *
 * @property position Current position in 2D space
 * @property velocity Current velocity vector
 * @property life Life remaining (1.0 = full, 0.0 = dead)
 * @property type The particle type determining appearance
 * @property glyph The character to render
 * @property age Time since spawn in seconds
 * @property attractor Optional attractor to pull particle toward a target
 */
data class Particle(
    var position: Vector2,
    var velocity: Vector2,
    var life: Float,
    val type: ParticleType,
    var glyph: Char,
    var age: Float = 0f,
    var attractor: ParticleAttractor? = null
) {
    /** Whether this particle is still alive */
    val isAlive: Boolean get() = life > 0f

    /**
     * Update particle state for one time step.
     *
     * @param deltaTime Time elapsed in seconds
     * @param drag Velocity dampening factor (0 = no drag, 1 = full stop)
     * @param gravity Gravity vector to apply
     * @param lifeDecay How much life to subtract per second
     */
    fun update(
        deltaTime: Float,
        drag: Float = 0f,
        gravity: Vector2 = Vector2.ZERO,
        lifeDecay: Float = 0.5f
    ) {
        // Apply attractor force if present
        attractor?.let { attr ->
            val toTarget = attr.target - position
            val distance = toTarget.length()
            if (distance > 0.1f) {
                val force = toTarget.normalized() * attr.strength * deltaTime
                velocity = velocity + force
            }
        }

        // Apply gravity
        velocity = velocity + gravity * deltaTime

        // Apply drag
        velocity = velocity * (1f - drag * deltaTime)

        // Update position
        position = position + velocity * deltaTime

        // Decay life
        life = (life - lifeDecay * deltaTime).coerceAtLeast(0f)

        // Track age
        age += deltaTime
    }

    companion object {
        /**
         * Glyph sets for different particle types.
         */
        object Glyphs {
            val MOTE_UNICODE = charArrayOf('\u00B7', '\u2218', '\u25E6') // · ∘ ◦
            val MOTE_ASCII = charArrayOf('.', 'o', 'O')

            val SPARK_UNICODE = charArrayOf('\u2726', '\u2727', '\u22C6', '\u2605') // ✦ ✧ ⋆ ★
            val SPARK_ASCII = charArrayOf('*', '+', 'x', '#')

            val TRAIL_UNICODE = charArrayOf('\u2022', '\u25CF', '\u25CB') // • ● ○
            val TRAIL_ASCII = charArrayOf('.', 'o', 'O')

            val RIPPLE_UNICODE = charArrayOf('\u25E6', '\u25CB', '\u25EF') // ◦ ○ ◯
            val RIPPLE_ASCII = charArrayOf('.', 'o', '0')

            /**
             * Get a glyph for the given type based on life.
             * Higher life = more prominent glyph.
             */
            fun forType(type: ParticleType, life: Float, useUnicode: Boolean = true): Char {
                val glyphs = when (type) {
                    ParticleType.MOTE -> if (useUnicode) MOTE_UNICODE else MOTE_ASCII
                    ParticleType.SPARK -> if (useUnicode) SPARK_UNICODE else SPARK_ASCII
                    ParticleType.TRAIL -> if (useUnicode) TRAIL_UNICODE else TRAIL_ASCII
                    ParticleType.RIPPLE -> if (useUnicode) RIPPLE_UNICODE else RIPPLE_ASCII
                }

                val index = when {
                    life > 0.66f -> 0
                    life > 0.33f -> 1
                    else -> glyphs.lastIndex
                }
                return glyphs[index.coerceIn(0, glyphs.lastIndex)]
            }
        }
    }
}
