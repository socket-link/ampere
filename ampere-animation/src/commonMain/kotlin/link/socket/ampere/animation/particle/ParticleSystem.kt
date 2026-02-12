package link.socket.ampere.animation.particle

import link.socket.ampere.animation.substrate.Point
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.roundToInt

/**
 * Manages a collection of particles with lifecycle and physics.
 *
 * The ParticleSystem provides:
 * - Particle spawning via emitters
 * - Physics updates (velocity, drag, gravity)
 * - Lifecycle management (life decay, despawning)
 * - Integration with SubstrateState (density influence)
 * - Performance-conscious batch updates
 *
 * @property maxParticles Maximum particles allowed (for performance)
 * @property drag Global drag coefficient (0 = no drag)
 * @property gravity Global gravity vector
 * @property lifeDecayRate Global life decay rate
 */
class ParticleSystem(
    private val maxParticles: Int = 1000,
    private val drag: Float = 0.1f,
    private val gravity: Vector2 = Vector2.ZERO,
    private val lifeDecayRate: Float = 0.5f
) {
    private val particles = mutableListOf<Particle>()

    /** Current particle count */
    val count: Int get() = particles.size

    /** Whether the system has any alive particles */
    val hasParticles: Boolean get() = particles.isNotEmpty()

    /**
     * Spawn particles using an emitter.
     *
     * @param emitter The emitter to use
     * @param count Number of particles to spawn
     * @param origin Starting position
     * @param config Emitter configuration
     */
    fun spawn(
        emitter: ParticleEmitter,
        count: Int,
        origin: Vector2,
        config: EmitterConfig = EmitterConfig()
    ) {
        val newParticles = emitter.emit(count, origin, config)
        addParticles(newParticles)
    }

    /**
     * Add particles directly to the system.
     */
    fun addParticles(newParticles: List<Particle>) {
        // Respect max particle limit
        val spaceAvailable = maxParticles - particles.size
        val toAdd = newParticles.take(spaceAvailable)
        particles.addAll(toAdd)
    }

    /**
     * Update all particles for one time step.
     *
     * @param deltaTime Time elapsed in seconds
     */
    fun update(deltaTime: Float) {
        // Update all particles (attractor physics handled internally by Particle)
        particles.forEach { particle ->
            particle.update(deltaTime, drag, gravity, lifeDecayRate)

            // Update glyph based on remaining life
            particle.glyph = Particle.Companion.Glyphs.forType(
                particle.type,
                particle.life,
                useUnicode = true
            )
        }

        // Remove dead particles
        particles.removeAll { !it.isAlive }
    }

    /**
     * Get all alive particles.
     */
    fun getParticles(): List<Particle> = particles.toList()

    /**
     * Get particles at or near a specific cell position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param radius Search radius
     */
    fun getParticlesNear(x: Int, y: Int, radius: Float = 0.5f): List<Particle> {
        return particles.filter { p ->
            val dx = p.position.x - x
            val dy = p.position.y - y
            dx * dx + dy * dy <= radius * radius
        }
    }

    /**
     * Clear all particles.
     */
    fun clear() {
        particles.clear()
    }

    /**
     * Fade all particles by reducing their life.
     *
     * @param rate Life reduction amount
     */
    fun fadeAll(rate: Float) {
        particles.forEach { p ->
            p.life = (p.life - rate).coerceAtLeast(0f)
        }
        particles.removeAll { !it.isAlive }
    }

    /**
     * Despawn particles near a specific point.
     *
     * @param center Center point
     * @param radius Radius around center to despawn
     */
    fun despawnNear(center: Vector2, radius: Float) {
        particles.removeAll { p ->
            val dx = p.position.x - center.x
            val dy = p.position.y - center.y
            dx * dx + dy * dy <= radius * radius
        }
    }

    /**
     * Add an attractor that pulls particles toward a point.
     *
     * @param target Attractor position
     * @param strength Pull strength
     */
    fun addAttractor(target: Vector2, strength: Float) {
        particles.forEach { p ->
            val toTarget = target - p.position
            val distance = toTarget.length()
            if (distance > 0.1f) {
                val force = toTarget.normalized() * strength
                p.velocity = p.velocity + force
            }
        }
    }

    /**
     * Apply noise-based movement to particles.
     *
     * @param strength Noise influence strength
     */
    fun applyNoise(strength: Float, time: Float = 0f) {
        // Simple pseudo-noise using particle properties
        particles.forEach { p ->
            val noiseX = kotlin.math.sin((p.position.x + time) * 0.5f) * strength
            val noiseY = kotlin.math.cos((p.position.y + time) * 0.5f) * strength
            p.velocity = p.velocity + Vector2(noiseX, noiseY)
        }
    }

    /**
     * Get the density contribution at a specific cell.
     *
     * Multiple particles at the same location increase density.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param maxInfluence Maximum density contribution per particle
     */
    fun getDensityAt(x: Int, y: Int, maxInfluence: Float = 0.3f): Float {
        var density = 0f
        particles.forEach { p ->
            val cellX = p.position.x.roundToInt()
            val cellY = p.position.y.roundToInt()
            if (cellX == x && cellY == y) {
                density += p.life * maxInfluence
            }
        }
        return density.coerceIn(0f, 1f)
    }

    /**
     * Update substrate density based on particle positions.
     *
     * @param substrate The substrate state to update
     * @param influence How much particles affect density
     */
    fun updateSubstrate(substrate: SubstrateState, influence: Float = 0.3f) {
        particles.forEach { p ->
            val cellX = p.position.x.roundToInt().coerceIn(0, substrate.width - 1)
            val cellY = p.position.y.roundToInt().coerceIn(0, substrate.height - 1)

            val currentDensity = substrate.getDensity(cellX, cellY)
            val newDensity = (currentDensity + p.life * influence).coerceIn(0f, 1f)
            substrate.setDensity(cellX, cellY, newDensity)
        }
    }

    companion object {
        /**
         * Create a burst of sparks at a location.
         */
        fun createSparkBurst(
            origin: Vector2,
            count: Int = 20,
            speed: Float = 3f
        ): ParticleSystem {
            val system = ParticleSystem()
            val emitter = BurstEmitter()
            val config = EmitterConfig(
                type = ParticleType.SPARK,
                speed = speed,
                speedVariance = speed * 0.5f,
                life = 1f,
                lifeVariance = 0.3f,
                spread = 360f
            )
            system.spawn(emitter, count, origin, config)
            return system
        }

        /**
         * Create a stream of particles in a direction.
         */
        fun createStream(
            origin: Vector2,
            direction: Float,
            count: Int = 10,
            speed: Float = 2f
        ): ParticleSystem {
            val system = ParticleSystem()
            val emitter = StreamEmitter()
            val config = EmitterConfig(
                type = ParticleType.TRAIL,
                speed = speed,
                speedVariance = speed * 0.3f,
                life = 1.5f,
                lifeVariance = 0.4f,
                spread = 20f,
                direction = direction
            )
            system.spawn(emitter, count, origin, config)
            return system
        }

        /**
         * Create an expanding ripple effect.
         */
        fun createRipple(
            origin: Vector2,
            count: Int = 24,
            speed: Float = 4f
        ): ParticleSystem {
            val system = ParticleSystem()
            val emitter = RippleEmitter()
            val config = EmitterConfig(
                type = ParticleType.RIPPLE,
                speed = speed,
                speedVariance = speed * 0.2f,
                life = 0.8f,
                lifeVariance = 0.2f
            )
            system.spawn(emitter, count, origin, config)
            return system
        }
    }
}

/**
 * Render particles to a character grid.
 *
 * @property width Grid width
 * @property height Grid height
 * @property useUnicode Whether to use Unicode characters
 */
class ParticleRenderer(
    private val width: Int,
    private val height: Int,
    private val useUnicode: Boolean = true
) {
    /**
     * Render particles to a 2D character grid.
     *
     * @param system The particle system to render
     * @param background Background character for empty cells
     * @return List of rows (strings)
     */
    fun render(system: ParticleSystem, background: Char = ' '): List<String> {
        // Create grid
        val grid = Array(height) { CharArray(width) { background } }

        // Plot particles
        system.getParticles().forEach { p ->
            val x = p.position.x.roundToInt()
            val y = p.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                grid[y][x] = p.glyph
            }
        }

        // Convert to strings
        return grid.map { it.concatToString() }
    }

    /**
     * Render particles with ANSI colors based on particle type.
     */
    fun renderColored(system: ParticleSystem, background: Char = ' '): List<String> {
        val grid = Array(height) { Array<Pair<Char, String?>>(width) { background to null } }

        system.getParticles().forEach { p ->
            val x = p.position.x.roundToInt()
            val y = p.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                val color = when (p.type) {
                    ParticleType.MOTE -> "\u001B[38;5;240m"  // Gray
                    ParticleType.SPARK -> "\u001B[38;5;226m" // Yellow
                    ParticleType.TRAIL -> "\u001B[38;5;45m"  // Cyan
                    ParticleType.RIPPLE -> "\u001B[38;5;51m" // Light cyan
                }
                grid[y][x] = p.glyph to color
            }
        }

        return grid.map { row ->
            buildString {
                row.forEach { (char, color) ->
                    if (color != null) {
                        append(color)
                        append(char)
                        append("\u001B[0m")
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
}
