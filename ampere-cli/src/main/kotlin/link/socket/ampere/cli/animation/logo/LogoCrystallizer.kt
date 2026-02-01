package link.socket.ampere.cli.animation.logo

import link.socket.ampere.cli.animation.particle.BurstEmitter
import link.socket.ampere.cli.animation.particle.EmitterConfig
import link.socket.ampere.cli.animation.particle.Particle
import link.socket.ampere.cli.animation.particle.ParticleSystem
import link.socket.ampere.cli.animation.particle.ParticleType
import link.socket.ampere.cli.animation.substrate.Vector2
import kotlin.math.roundToInt

/**
 * Phases of the logo crystallization animation.
 */
enum class CrystallizationPhase {
    /** Random particles scatter across screen */
    SCATTERED,
    /** Particles flow toward logo center */
    FLOW,
    /** Particles cluster, density increases */
    DENSITY,
    /** Logo glyphs appear where density exceeds threshold */
    FORM,
    /** Remaining particles disperse, logo stabilizes */
    SETTLE,
    /** Animation complete */
    COMPLETE
}

/**
 * Listener for crystallization events.
 */
typealias CrystallizationListener = (CrystallizationEvent) -> Unit

/**
 * Events emitted during crystallization.
 */
sealed class CrystallizationEvent {
    data class PhaseChanged(val phase: CrystallizationPhase) : CrystallizationEvent()
    data class GlyphRevealed(val glyph: GlyphPosition) : CrystallizationEvent()
    object Complete : CrystallizationEvent()
}

/**
 * Orchestrates the logo crystallization animation.
 *
 * The crystallization proceeds through phases:
 * 1. SCATTERED: Random particles with noise movement
 * 2. FLOW: Particles attracted toward logo center
 * 3. DENSITY: Strong attraction, particles cluster
 * 4. FORM: Glyphs reveal as density thresholds are met
 * 5. SETTLE: Remaining particles fade, logo stabilizes
 *
 * @property particles The particle system to use
 * @property logoGlyphs The glyph positions that form the logo
 * @property screenWidth Width of the render area
 * @property screenHeight Height of the render area
 */
class LogoCrystallizer(
    private val particles: ParticleSystem,
    private val logoGlyphs: List<GlyphPosition>,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    /** Current crystallization phase */
    var phase: CrystallizationPhase = CrystallizationPhase.SCATTERED
        private set

    /** Total elapsed time */
    var elapsedTime: Float = 0f
        private set

    /** Progress through current phase (0-1) */
    val phaseProgress: Float
        get() = when (phase) {
            CrystallizationPhase.SCATTERED -> (elapsedTime / SCATTERED_DURATION).coerceIn(0f, 1f)
            CrystallizationPhase.FLOW -> ((elapsedTime - SCATTERED_DURATION) / FLOW_DURATION).coerceIn(0f, 1f)
            CrystallizationPhase.DENSITY -> ((elapsedTime - SCATTERED_DURATION - FLOW_DURATION) / DENSITY_DURATION).coerceIn(0f, 1f)
            CrystallizationPhase.FORM -> ((elapsedTime - SCATTERED_DURATION - FLOW_DURATION - DENSITY_DURATION) / FORM_DURATION).coerceIn(0f, 1f)
            CrystallizationPhase.SETTLE -> ((elapsedTime - SCATTERED_DURATION - FLOW_DURATION - DENSITY_DURATION - FORM_DURATION) / SETTLE_DURATION).coerceIn(0f, 1f)
            CrystallizationPhase.COMPLETE -> 1f
        }

    /** Overall progress (0-1) */
    val overallProgress: Float
        get() = (elapsedTime / TOTAL_DURATION).coerceIn(0f, 1f)

    /** Number of revealed glyphs */
    val revealedGlyphCount: Int
        get() = logoGlyphs.count { it.visible }

    /** Whether all glyphs have been revealed */
    val allGlyphsRevealed: Boolean
        get() = logoGlyphs.all { it.visible }

    /** Whether the animation is complete */
    val isComplete: Boolean
        get() = phase == CrystallizationPhase.COMPLETE

    private val listeners = mutableListOf<CrystallizationListener>()
    private var noiseTime = 0f
    private var initialized = false

    /**
     * Initialize with scattered particles.
     *
     * @param particleCount Number of particles to spawn
     */
    fun initialize(particleCount: Int = 100) {
        if (initialized) return

        // Spawn particles randomly across the screen
        val emitter = BurstEmitter()
        val config = EmitterConfig(
            type = ParticleType.MOTE,
            speed = 1.5f,
            speedVariance = 0.5f,
            life = 5f,  // Long life to survive until form phase
            lifeVariance = 1f,
            spread = 360f
        )

        // Spawn particles in clusters across the screen
        val clusterCount = 5
        for (i in 0 until clusterCount) {
            val clusterX = (screenWidth * (i + 1)) / (clusterCount + 1).toFloat()
            val clusterY = (screenHeight * kotlin.random.Random.nextFloat()).coerceIn(2f, screenHeight - 2f)
            particles.spawn(emitter, particleCount / clusterCount, Vector2(clusterX, clusterY), config)
        }

        // Reset all glyph visibility
        logoGlyphs.forEach { it.visible = false }

        initialized = true
    }

    /**
     * Update the crystallization animation.
     *
     * @param deltaTime Time elapsed since last update in seconds
     */
    fun update(deltaTime: Float) {
        if (!initialized) {
            initialize()
        }

        val previousPhase = phase
        elapsedTime += deltaTime
        noiseTime += deltaTime

        // Determine current phase based on elapsed time
        phase = when {
            elapsedTime < SCATTERED_DURATION -> CrystallizationPhase.SCATTERED
            elapsedTime < SCATTERED_DURATION + FLOW_DURATION -> CrystallizationPhase.FLOW
            elapsedTime < SCATTERED_DURATION + FLOW_DURATION + DENSITY_DURATION -> CrystallizationPhase.DENSITY
            elapsedTime < SCATTERED_DURATION + FLOW_DURATION + DENSITY_DURATION + FORM_DURATION -> CrystallizationPhase.FORM
            elapsedTime < TOTAL_DURATION -> CrystallizationPhase.SETTLE
            else -> CrystallizationPhase.COMPLETE
        }

        // Emit phase change event
        if (phase != previousPhase) {
            emit(CrystallizationEvent.PhaseChanged(phase))
        }

        // Apply phase-specific behavior
        when (phase) {
            CrystallizationPhase.SCATTERED -> updateScattered(deltaTime)
            CrystallizationPhase.FLOW -> updateFlow(deltaTime)
            CrystallizationPhase.DENSITY -> updateDensity(deltaTime)
            CrystallizationPhase.FORM -> updateForm(deltaTime)
            CrystallizationPhase.SETTLE -> updateSettle(deltaTime)
            CrystallizationPhase.COMPLETE -> {
                if (previousPhase != CrystallizationPhase.COMPLETE) {
                    emit(CrystallizationEvent.Complete)
                }
            }
        }

        // Update particle system
        particles.update(deltaTime)
    }

    /**
     * Get the center position of the logo.
     */
    fun getLogoCenter(): Vector2 {
        if (logoGlyphs.isEmpty()) return Vector2(screenWidth / 2f, screenHeight / 2f)

        val sumX = logoGlyphs.sumOf { it.position.x.toDouble() }
        val sumY = logoGlyphs.sumOf { it.position.y.toDouble() }
        return Vector2(
            (sumX / logoGlyphs.size).toFloat(),
            (sumY / logoGlyphs.size).toFloat()
        )
    }

    /**
     * Get visible glyphs for rendering.
     */
    fun getVisibleGlyphs(): List<GlyphPosition> = logoGlyphs.filter { it.visible }

    /**
     * Reset the crystallization to start over.
     */
    fun reset() {
        phase = CrystallizationPhase.SCATTERED
        elapsedTime = 0f
        noiseTime = 0f
        initialized = false
        logoGlyphs.forEach { it.visible = false }
        particles.clear()
    }

    /**
     * Add an event listener.
     */
    fun addListener(listener: CrystallizationListener) {
        listeners.add(listener)
    }

    /**
     * Remove an event listener.
     */
    fun removeListener(listener: CrystallizationListener) {
        listeners.remove(listener)
    }

    private fun emit(event: CrystallizationEvent) {
        listeners.forEach { it(event) }
    }

    private fun updateScattered(deltaTime: Float) {
        // Apply noise for organic movement
        particles.applyNoise(strength = 0.3f, time = noiseTime)
    }

    private fun updateFlow(deltaTime: Float) {
        // Begin attracting particles toward logo center
        val center = getLogoCenter()
        val strength = 1f + phaseProgress * 2f  // Increasing strength

        particles.addAttractor(center, strength * deltaTime * 60f)

        // Still apply some noise for organic feel
        particles.applyNoise(strength = 0.15f * (1f - phaseProgress), time = noiseTime)
    }

    private fun updateDensity(deltaTime: Float) {
        // Strong attraction to glyph positions
        val attractorStrength = 3f + phaseProgress * 2f

        // Attract to multiple points for better distribution
        Logo.getAttractorPositions(
            getLogoCenter().x,
            getLogoCenter().y
        ).forEach { (pos, relativeStrength) ->
            particles.addAttractor(pos, attractorStrength * relativeStrength * deltaTime * 60f)
        }
    }

    private fun updateForm(deltaTime: Float) {
        // Check each unrevealed glyph
        logoGlyphs.filter { !it.visible }.forEach { glyph ->
            val density = particles.getDensityAt(
                glyph.position.x.roundToInt(),
                glyph.position.y.roundToInt(),
                maxInfluence = 0.4f
            )

            // Also check nearby positions for density
            val nearbyDensity = listOf(
                Vector2(-1f, 0f), Vector2(1f, 0f),
                Vector2(0f, -1f), Vector2(0f, 1f)
            ).map { offset ->
                particles.getDensityAt(
                    (glyph.position.x + offset.x).roundToInt(),
                    (glyph.position.y + offset.y).roundToInt(),
                    maxInfluence = 0.2f
                )
            }.maxOrNull() ?: 0f

            val totalDensity = density + nearbyDensity * 0.5f

            // Lower threshold as phase progresses to ensure all glyphs reveal
            val adjustedThreshold = glyph.densityThreshold * (1f - phaseProgress * 0.5f)

            if (totalDensity >= adjustedThreshold || phaseProgress > 0.8f) {
                glyph.visible = true
                // Despawn particles near the revealed glyph
                particles.despawnNear(glyph.position, radius = 1.5f)
                emit(CrystallizationEvent.GlyphRevealed(glyph))
            }
        }

        // Continue light attraction
        val center = getLogoCenter()
        particles.addAttractor(center, 1f * deltaTime * 60f)
    }

    private fun updateSettle(deltaTime: Float) {
        // Reveal any remaining glyphs
        logoGlyphs.filter { !it.visible }.forEach { glyph ->
            glyph.visible = true
            particles.despawnNear(glyph.position, radius = 1f)
            emit(CrystallizationEvent.GlyphRevealed(glyph))
        }

        // Fade remaining particles
        particles.fadeAll(rate = deltaTime * 2f)
    }

    companion object {
        /** Phase durations in seconds */
        const val SCATTERED_DURATION = 0.5f
        const val FLOW_DURATION = 0.5f
        const val DENSITY_DURATION = 0.5f
        const val FORM_DURATION = 0.5f
        const val SETTLE_DURATION = 0.5f

        const val TOTAL_DURATION = SCATTERED_DURATION + FLOW_DURATION +
            DENSITY_DURATION + FORM_DURATION + SETTLE_DURATION

        /**
         * Create a LogoCrystallizer with the default logo.
         */
        fun create(
            screenWidth: Int,
            screenHeight: Int,
            particles: ParticleSystem = ParticleSystem()
        ): LogoCrystallizer {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f

            val logoGlyphs = Logo.getLogoForSize(
                screenWidth,
                screenHeight,
                centerX,
                centerY
            )

            return LogoCrystallizer(
                particles = particles,
                logoGlyphs = logoGlyphs,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }
}
