package link.socket.ampere.cli.animation.substrate

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Animates the substrate using Perlin noise for organic ambient motion.
 *
 * The animator applies smooth, natural-looking variation to the density field
 * even when idle, creating a sense of latent activity within the substrate.
 *
 * @property baseDensity Base density level (0.0-1.0)
 * @property noiseScale Scale factor for noise sampling (higher = more detail)
 * @property noiseAmplitude How much noise affects density (0.0-1.0)
 * @property timeScale Speed of ambient animation
 */
class SubstrateAnimator(
    private val baseDensity: Float = 0.3f,
    private val noiseScale: Float = 0.1f,
    private val noiseAmplitude: Float = 0.2f,
    private val timeScale: Float = 0.05f,
    seed: Long = System.currentTimeMillis()
) {
    private val noise = PerlinNoise(seed)

    /**
     * Update the substrate state with ambient animation.
     *
     * @param state Current substrate state
     * @param deltaTime Time elapsed since last update (in seconds)
     * @return Updated substrate state
     */
    fun updateAmbient(state: SubstrateState, deltaTime: Float): SubstrateState {
        val newTime = state.time + deltaTime
        val densityField = state.densityField.copyOf()

        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                val idx = y * state.width + x

                // Sample noise at this position and time
                val noiseValue = noise.sample(
                    x * noiseScale,
                    y * noiseScale,
                    newTime * timeScale
                )

                // Apply noise to base density
                val baseDensityValue = baseDensity + noiseValue * noiseAmplitude

                // Apply hotspot influence
                var hotspotInfluence = 0f
                for (hotspot in state.activityHotspots) {
                    val dx = x - hotspot.x
                    val dy = y - hotspot.y
                    val distance = sqrt((dx * dx + dy * dy).toFloat())
                    // Gaussian falloff from hotspot
                    val influence = exp(-distance * distance / 16f) * 0.5f
                    hotspotInfluence += influence
                }

                densityField[idx] = (baseDensityValue + hotspotInfluence).coerceIn(0f, 1f)
            }
        }

        return state.copy(
            densityField = densityField,
            time = newTime
        )
    }

    /**
     * Create a pulse effect at a specific location.
     *
     * @param state Current substrate state
     * @param center Center of the pulse
     * @param intensity Pulse intensity (0.0-1.0)
     * @param radius Radius of effect
     * @return Updated substrate state
     */
    fun pulse(
        state: SubstrateState,
        center: Point,
        intensity: Float = 0.5f,
        radius: Float = 5f
    ): SubstrateState {
        val densityField = state.densityField.copyOf()

        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                val dx = x - center.x
                val dy = y - center.y
                val distance = sqrt((dx * dx + dy * dy).toFloat())

                if (distance <= radius) {
                    val idx = y * state.width + x
                    // Smooth falloff using cosine
                    val falloff = (1 - distance / radius)
                    val pulseValue = intensity * falloff * falloff
                    densityField[idx] = (densityField[idx] + pulseValue).coerceIn(0f, 1f)
                }
            }
        }

        return state.copy(densityField = densityField)
    }

    /**
     * Ripple effect that expands outward from a point.
     *
     * @param state Current substrate state
     * @param center Center of the ripple
     * @param phase Current phase of the ripple (0.0 to 1.0)
     * @param maxRadius Maximum radius of the ripple
     * @param intensity Ripple intensity
     * @return Updated substrate state
     */
    fun ripple(
        state: SubstrateState,
        center: Point,
        phase: Float,
        maxRadius: Float = 10f,
        intensity: Float = 0.3f
    ): SubstrateState {
        val densityField = state.densityField.copyOf()
        val currentRadius = maxRadius * phase
        val ringWidth = 2f

        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                val dx = x - center.x
                val dy = y - center.y
                val distance = sqrt((dx * dx + dy * dy).toFloat())

                // Ring at current radius
                val distFromRing = kotlin.math.abs(distance - currentRadius)
                if (distFromRing <= ringWidth) {
                    val idx = y * state.width + x
                    val ringIntensity = intensity * (1 - distFromRing / ringWidth) * (1 - phase)
                    densityField[idx] = (densityField[idx] + ringIntensity).coerceIn(0f, 1f)
                }
            }
        }

        return state.copy(densityField = densityField)
    }

    /**
     * Update flow field to point toward a target.
     *
     * @param state Current substrate state
     * @param target Target point for flow
     * @param strength Flow strength (0.0-1.0)
     * @return Updated substrate state
     */
    fun flowToward(
        state: SubstrateState,
        target: Point,
        strength: Float = 0.5f
    ): SubstrateState {
        val flowField = state.flowField.copyOf()

        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                val idx = y * state.width + x
                val dx = target.x - x
                val dy = target.y - y
                val distance = sqrt((dx * dx + dy * dy).toFloat())

                if (distance > 0.1f) {
                    val direction = Vector2(dx / distance, dy / distance)
                    flowField[idx] = flowField[idx].lerp(direction * strength, 0.3f)
                }
            }
        }

        return state.copy(flowField = flowField)
    }

    /**
     * Increase density along a path between two points.
     *
     * @param state Current substrate state
     * @param from Start point
     * @param to End point
     * @param intensity Path intensity
     * @param width Path width
     * @return Updated substrate state
     */
    fun createPath(
        state: SubstrateState,
        from: Point,
        to: Point,
        intensity: Float = 0.4f,
        width: Float = 2f
    ): SubstrateState {
        val densityField = state.densityField.copyOf()

        val dx = to.x - from.x
        val dy = to.y - from.y
        val pathLength = sqrt((dx * dx + dy * dy).toFloat())

        if (pathLength < 0.1f) return state

        val dirX = dx / pathLength
        val dirY = dy / pathLength

        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                // Project point onto path line
                val px = x - from.x
                val py = y - from.y
                val projection = px * dirX + py * dirY

                // Check if projection is within path segment
                if (projection >= 0 && projection <= pathLength) {
                    // Calculate perpendicular distance to path
                    val closestX = from.x + dirX * projection
                    val closestY = from.y + dirY * projection
                    val distToPath = sqrt((x - closestX) * (x - closestX) + (y - closestY) * (y - closestY))

                    if (distToPath <= width) {
                        val idx = y * state.width + x
                        val falloff = 1 - distToPath / width
                        densityField[idx] = (densityField[idx] + intensity * falloff).coerceIn(0f, 1f)
                    }
                }
            }
        }

        return state.copy(densityField = densityField)
    }

    /**
     * Clear all activity from the substrate.
     */
    fun clear(state: SubstrateState): SubstrateState {
        return state.copy(
            densityField = FloatArray(state.width * state.height) { baseDensity },
            flowField = Array(state.width * state.height) { Vector2.ZERO },
            activityHotspots = emptyList()
        )
    }
}
