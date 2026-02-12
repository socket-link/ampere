package link.socket.ampere.animation.particle

import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Configuration for particle emitters.
 */
data class EmitterConfig(
    val type: ParticleType = ParticleType.MOTE,
    val speed: Float = 1f,
    val speedVariance: Float = 0.5f,
    val life: Float = 1f,
    val lifeVariance: Float = 0.3f,
    val spread: Float = 360f,  // Angle spread in degrees
    val direction: Float = 0f,  // Base direction in degrees
    val useUnicode: Boolean = true
)

/**
 * Interface for particle emitters.
 *
 * Emitters create particles with specific patterns and behaviors.
 */
interface ParticleEmitter {
    /**
     * Emit particles from an origin point.
     *
     * @param count Number of particles to emit
     * @param origin Starting position for particles
     * @param config Emission configuration
     * @return List of newly created particles
     */
    fun emit(count: Int, origin: Vector2, config: EmitterConfig): List<Particle>
}

/**
 * Emits particles in a radial burst pattern.
 *
 * All particles explode outward from the origin in random directions
 * within the configured spread angle.
 */
class BurstEmitter(private val random: Random = Random.Default) : ParticleEmitter {

    override fun emit(count: Int, origin: Vector2, config: EmitterConfig): List<Particle> {
        return buildList {
            for (i in 0 until count) {
                // Calculate direction with spread
                val baseAngle = config.direction.toDouble() * PI / 180.0
                val spreadRad = config.spread.toDouble() * PI / 180.0
                val angle = baseAngle + (random.nextFloat() - 0.5f) * spreadRad

                // Calculate speed with variance
                val speed = config.speed + (random.nextFloat() - 0.5f) * config.speedVariance * 2

                // Create velocity from angle and speed
                val velocity = Vector2(
                    cos(angle).toFloat() * speed,
                    sin(angle).toFloat() * speed
                )

                // Calculate life with variance
                val life = (config.life + (random.nextFloat() - 0.5f) * config.lifeVariance * 2)
                    .coerceIn(0.1f, 2f)

                val glyph = Particle.Companion.Glyphs.forType(config.type, life, config.useUnicode)

                add(Particle(
                    position = origin.copy(),
                    velocity = velocity,
                    life = life,
                    type = config.type,
                    glyph = glyph
                ))
            }
        }
    }
}

/**
 * Emits particles in a directional stream.
 *
 * Particles flow in a specific direction with some randomness,
 * creating a stream or jet effect.
 */
class StreamEmitter(private val random: Random = Random.Default) : ParticleEmitter {

    override fun emit(count: Int, origin: Vector2, config: EmitterConfig): List<Particle> {
        return buildList {
            // Narrow spread for stream effect
            val effectiveSpread = config.spread.coerceAtMost(45f)

            for (i in 0 until count) {
                val baseAngle = config.direction.toDouble() * PI / 180.0
                val spreadRad = effectiveSpread.toDouble() * PI / 180.0
                val angle = baseAngle + (random.nextFloat() - 0.5f) * spreadRad

                val speed = config.speed + random.nextFloat() * config.speedVariance

                val velocity = Vector2(
                    cos(angle).toFloat() * speed,
                    sin(angle).toFloat() * speed
                )

                val life = config.life + random.nextFloat() * config.lifeVariance

                // Stagger particle starting positions slightly along the stream
                val offset = Vector2(
                    cos(baseAngle).toFloat() * random.nextFloat() * 2f,
                    sin(baseAngle).toFloat() * random.nextFloat() * 2f
                )

                val glyph = Particle.Companion.Glyphs.forType(config.type, life, config.useUnicode)

                add(Particle(
                    position = origin + offset,
                    velocity = velocity,
                    life = life,
                    type = config.type,
                    glyph = glyph
                ))
            }
        }
    }
}

/**
 * Emits particles that are attracted to a target point.
 *
 * Particles start scattered but gradually move toward the attractor.
 */
class AttractorEmitter(
    private val random: Random = Random.Default,
    private val target: Vector2,
    private val attractorStrength: Float = 0.5f,
    private val scatterRadius: Float = 10f
) : ParticleEmitter {

    override fun emit(count: Int, origin: Vector2, config: EmitterConfig): List<Particle> {
        return buildList {
            for (i in 0 until count) {
                // Scatter initial position around origin
                val angle = random.nextFloat() * 2 * PI.toFloat()
                val radius = random.nextFloat() * scatterRadius
                val scatteredPosition = origin + Vector2(
                    cos(angle) * radius,
                    sin(angle) * radius
                )

                // Initial velocity points generally toward target
                val toTarget = target - scatteredPosition
                val distance = toTarget.length()
                val baseVelocity = if (distance > 0.1f) {
                    toTarget.normalized() * config.speed * random.nextFloat()
                } else {
                    Vector2.ZERO
                }

                // Add some randomness
                val randomVelocity = Vector2(
                    (random.nextFloat() - 0.5f) * config.speedVariance,
                    (random.nextFloat() - 0.5f) * config.speedVariance
                )

                val life = config.life + (random.nextFloat() - 0.5f) * config.lifeVariance * 2
                val glyph = Particle.Companion.Glyphs.forType(config.type, life, config.useUnicode)

                add(Particle(
                    position = scatteredPosition,
                    velocity = baseVelocity + randomVelocity,
                    life = life.coerceIn(0.1f, 2f),
                    type = config.type,
                    glyph = glyph,
                    attractor = ParticleAttractor(target, attractorStrength)
                ))
            }
        }
    }
}

/**
 * Creates a ring of particles expanding outward.
 */
class RippleEmitter(private val random: Random = Random.Default) : ParticleEmitter {

    override fun emit(count: Int, origin: Vector2, config: EmitterConfig): List<Particle> {
        return buildList {
            val angleStep = 2 * PI / count

            for (i in 0 until count) {
                val angle = i * angleStep + random.nextFloat() * 0.2f

                val speed = config.speed + (random.nextFloat() - 0.5f) * config.speedVariance

                val velocity = Vector2(
                    cos(angle).toFloat() * speed,
                    sin(angle).toFloat() * speed
                )

                val life = config.life + (random.nextFloat() - 0.5f) * config.lifeVariance * 2
                val glyph = Particle.Companion.Glyphs.forType(ParticleType.RIPPLE, life, config.useUnicode)

                add(Particle(
                    position = origin.copy(),
                    velocity = velocity,
                    life = life.coerceIn(0.1f, 2f),
                    type = ParticleType.RIPPLE,
                    glyph = glyph
                ))
            }
        }
    }
}
