package link.socket.ampere.animation.agent

import link.socket.ampere.animation.particle.*
import link.socket.ampere.animation.substrate.Point
import link.socket.ampere.animation.substrate.SubstrateAnimator
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Translates cognitive phase transitions into particle choreography.
 *
 * Each PROPEL phase has a distinct visual signature:
 * - PERCEIVE: Particles drift inward from edges toward the agent (sensory gathering)
 * - RECALL: Existing particles near agent brighten and cluster (memory activation)
 * - PLAN: Particles form tentative structures, testing arrangements (strategy formation)
 * - EXECUTE: Particles align and accelerate outward (discharge/committed action)
 * - EVALUATE: Particles slow, some fade, some persist as new anchors (reflection)
 * - LOOP: Brief stillness, then subtle pulse (cycle reset)
 *
 * The choreographer observes phase transitions on agents and orchestrates particle
 * effects through the existing [ParticleSystem] and [SubstrateAnimator] APIs.
 * It doesn't render — it shapes the physics.
 *
 * @property particles The particle system to choreograph
 * @property substrateAnimator The substrate animator for density/flow effects
 */
class CognitiveChoreographer(
    private val particles: ParticleSystem,
    private val substrateAnimator: SubstrateAnimator
) {
    private val previousPhases = mutableMapOf<String, CognitivePhase>()

    /** Accumulated time for continuous effects */
    private var time: Float = 0f

    /** Cooldown tracking per agent to avoid particle spam */
    private val spawnCooldowns = mutableMapOf<String, Float>()

    companion object {
        /** Minimum interval between particle spawns per agent (seconds) */
        private const val SPAWN_COOLDOWN = 0.15f

        /** Number of compass directions for PERCEIVE inward streams */
        private const val PERCEIVE_DIRECTIONS = 8

        /** Radius from which PERCEIVE particles originate */
        private const val PERCEIVE_SPAWN_RADIUS = 12f

        /** Number of candidate positions for PLAN structures */
        private const val PLAN_CLUSTER_COUNT = 3

        /** Radius for PLAN cluster offsets */
        private const val PLAN_CLUSTER_RADIUS = 6f

        /** Radius for LOOP despawn area */
        private const val LOOP_DESPAWN_RADIUS = 3f

        /** Radius for EVALUATE fade effect */
        private const val EVALUATE_FADE_RADIUS = 10f

        /** Particle count per PERCEIVE stream direction */
        private const val PERCEIVE_PARTICLES_PER_DIR = 2

        /** Particle count for EXECUTE burst */
        private const val EXECUTE_BURST_COUNT = 15

        /** Particle count for transition spark burst */
        private const val TRANSITION_SPARK_COUNT = 10
    }

    /**
     * Update choreography based on current agent states.
     *
     * Detects phase transitions, triggers transition effects, and applies
     * continuous phase-specific particle and substrate behaviors.
     *
     * @param agents Current agent layer state
     * @param substrate Current substrate state
     * @param deltaTime Time step in seconds
     * @return Updated substrate state
     */
    fun update(
        agents: AgentLayer,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        time += deltaTime
        var currentSubstrate = substrate

        // Decay cooldowns
        spawnCooldowns.keys.toList().forEach { id ->
            val remaining = (spawnCooldowns[id] ?: 0f) - deltaTime
            if (remaining <= 0f) spawnCooldowns.remove(id)
            else spawnCooldowns[id] = remaining
        }

        for (agent in agents.allAgents) {
            val previousPhase = previousPhases[agent.id] ?: CognitivePhase.NONE
            val currentPhase = agent.cognitivePhase

            // Handle phase transitions
            if (currentPhase != previousPhase) {
                currentSubstrate = onPhaseTransition(
                    agent, previousPhase, currentPhase, currentSubstrate
                )
                previousPhases[agent.id] = currentPhase
            }

            // Apply continuous phase effects
            currentSubstrate = applyContinuousEffects(
                agent, currentPhase, currentSubstrate, deltaTime
            )
        }

        // Clean up tracking for removed agents
        val activeIds = agents.allAgents.map { it.id }.toSet()
        previousPhases.keys.retainAll(activeIds)
        spawnCooldowns.keys.retainAll(activeIds)

        return currentSubstrate
    }

    /**
     * Handle one-shot effects when a phase transition occurs.
     */
    private fun onPhaseTransition(
        agent: AgentVisualState,
        from: CognitivePhase,
        to: CognitivePhase,
        substrate: SubstrateState
    ): SubstrateState {
        var result = substrate

        // Any → EXECUTE: Spark burst (discharge moment)
        if (to == CognitivePhase.EXECUTE) {
            spawnSparkBurst(agent.position, TRANSITION_SPARK_COUNT)
        }

        // EXECUTE → EVALUATE: Trail particles along the executed flow connection
        if (from == CognitivePhase.EXECUTE && to == CognitivePhase.EVALUATE) {
            spawnTrailFromAgent(agent.position)
        }

        // Any → PERCEIVE: Subtle ripple outward (attention widening)
        if (to == CognitivePhase.PERCEIVE) {
            val center = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
            result = substrateAnimator.ripple(result, center, phase = 0.1f, maxRadius = 8f, intensity = 0.2f)
        }

        return result
    }

    /**
     * Apply continuous effects for the current phase each frame.
     */
    private fun applyContinuousEffects(
        agent: AgentVisualState,
        phase: CognitivePhase,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        return when (phase) {
            CognitivePhase.PERCEIVE -> applyPerceive(agent, substrate, deltaTime)
            CognitivePhase.RECALL -> applyRecall(agent, substrate, deltaTime)
            CognitivePhase.PLAN -> applyPlan(agent, substrate, deltaTime)
            CognitivePhase.EXECUTE -> applyExecute(agent, substrate, deltaTime)
            CognitivePhase.EVALUATE -> applyEvaluate(agent, substrate, deltaTime)
            CognitivePhase.LOOP -> applyLoop(agent, substrate, deltaTime)
            CognitivePhase.NONE -> substrate
        }
    }

    // ── PERCEIVE: Particles drift inward from edges toward agent ──

    private fun applyPerceive(
        agent: AgentVisualState,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        if (canSpawn(agent.id)) {
            val angleStep = 2 * PI / PERCEIVE_DIRECTIONS
            for (i in 0 until PERCEIVE_DIRECTIONS) {
                val angle = i * angleStep
                val spawnPos = Vector2(
                    agent.position.x + cos(angle).toFloat() * PERCEIVE_SPAWN_RADIUS,
                    agent.position.y + sin(angle).toFloat() * PERCEIVE_SPAWN_RADIUS
                )

                // Direction points inward toward agent
                val inwardAngle = angle + PI // Reverse direction
                val emitter = StreamEmitter()
                val config = EmitterConfig(
                    type = ParticleType.MOTE,
                    speed = 1.5f,
                    speedVariance = 0.5f,
                    life = 1.2f,
                    lifeVariance = 0.3f,
                    spread = 15f,
                    direction = (inwardAngle * 180.0 / PI).toFloat()
                )
                particles.spawn(emitter, PERCEIVE_PARTICLES_PER_DIR, spawnPos, config)
            }
            markSpawned(agent.id)
        }

        // Substrate: flow toward agent
        val center = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
        return substrateAnimator.flowToward(substrate, center, strength = 0.3f)
    }

    // ── RECALL: Existing particles brighten and cluster (memory activation) ──

    private fun applyRecall(
        agent: AgentVisualState,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        // Pull nearby particles toward agent
        particles.addAttractor(agent.position, strength = 0.8f * deltaTime)

        // Boost life of nearby particles (brightening)
        val nearbyParticles = particles.getParticlesNear(
            agent.position.x.roundToInt(),
            agent.position.y.roundToInt(),
            radius = EVALUATE_FADE_RADIUS
        )
        nearbyParticles.forEach { p ->
            p.life = (p.life + 0.3f * deltaTime).coerceAtMost(1f)
        }

        // Substrate: warm pulse at agent position
        val center = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
        return substrateAnimator.pulse(substrate, center, intensity = 0.3f * deltaTime, radius = 5f)
    }

    // ── PLAN: Tentative structures testing formations ──

    private fun applyPlan(
        agent: AgentVisualState,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        var result = substrate

        if (canSpawn(agent.id)) {
            // Spawn small burst clusters at offset positions around agent
            val angleStep = 2 * PI / PLAN_CLUSTER_COUNT
            for (i in 0 until PLAN_CLUSTER_COUNT) {
                val angle = i * angleStep + time * 0.5f // Slowly rotate
                val clusterPos = Vector2(
                    agent.position.x + cos(angle).toFloat() * PLAN_CLUSTER_RADIUS,
                    agent.position.y + sin(angle).toFloat() * PLAN_CLUSTER_RADIUS
                )

                val emitter = BurstEmitter()
                val config = EmitterConfig(
                    type = ParticleType.MOTE,
                    speed = 0.8f,
                    speedVariance = 0.4f,
                    life = 0.8f,
                    lifeVariance = 0.2f,
                    spread = 120f
                )
                particles.spawn(emitter, 3, clusterPos, config)
            }
            markSpawned(agent.id)
        }

        // Competing attractors at cluster positions pull particles between them
        val angleStep = 2 * PI / PLAN_CLUSTER_COUNT
        for (i in 0 until PLAN_CLUSTER_COUNT) {
            val angle = i * angleStep + time * 0.5f
            val attractorPos = Vector2(
                agent.position.x + cos(angle).toFloat() * PLAN_CLUSTER_RADIUS,
                agent.position.y + sin(angle).toFloat() * PLAN_CLUSTER_RADIUS
            )
            particles.addAttractor(attractorPos, strength = 0.4f * deltaTime)
        }

        // Substrate: paths between agent and candidate positions
        val agentPoint = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
        for (i in 0 until PLAN_CLUSTER_COUNT) {
            val angle = i * angleStep + time * 0.5f
            val targetPoint = Point(
                (agent.position.x + cos(angle).toFloat() * PLAN_CLUSTER_RADIUS).roundToInt(),
                (agent.position.y + sin(angle).toFloat() * PLAN_CLUSTER_RADIUS).roundToInt()
            )
            result = substrateAnimator.createPath(result, agentPoint, targetPoint, intensity = 0.2f)
        }

        return result
    }

    // ── EXECUTE: Particles align and accelerate outward (discharge) ──

    private fun applyExecute(
        agent: AgentVisualState,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        if (canSpawn(agent.id)) {
            // High-speed focused stream outward from agent
            val emitter = StreamEmitter()
            val config = EmitterConfig(
                type = ParticleType.SPARK,
                speed = 5f,
                speedVariance = 1.5f,
                life = 0.8f,
                lifeVariance = 0.2f,
                spread = 30f,
                direction = (agent.phaseProgress * 360f) // Sweep direction with progress
            )
            particles.spawn(emitter, 4, agent.position, config)
            markSpawned(agent.id)
        }

        // Substrate: expanding ripple from agent
        val center = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
        return substrateAnimator.ripple(
            substrate, center,
            phase = agent.phaseProgress.coerceIn(0f, 1f),
            maxRadius = 12f,
            intensity = 0.4f
        )
    }

    // ── EVALUATE: Afterglow, particles slow and persist (reflection) ──

    private fun applyEvaluate(
        agent: AgentVisualState,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        // Gentle fade on nearby particles
        val nearbyParticles = particles.getParticlesNear(
            agent.position.x.roundToInt(),
            agent.position.y.roundToInt(),
            radius = EVALUATE_FADE_RADIUS
        )
        nearbyParticles.forEach { p ->
            // Slow particles down (drag effect)
            p.velocity = p.velocity * (1f - 0.5f * deltaTime)
            // Some particles fade, some get anchored (persist)
            if (p.life < 0.5f) {
                p.life = (p.life - 0.1f * deltaTime).coerceAtLeast(0f)
            } else {
                // Anchor: keep life stable (memory formation)
                p.attractor = ParticleAttractor(agent.position, 0.2f)
            }
        }

        // Substrate: persistent density increase at agent position
        val center = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
        return substrateAnimator.pulse(substrate, center, intensity = 0.15f * deltaTime, radius = 4f)
    }

    // ── LOOP: Brief stillness, then subtle pulse (cycle reset) ──

    private fun applyLoop(
        agent: AgentVisualState,
        substrate: SubstrateState,
        deltaTime: Float
    ): SubstrateState {
        // Despawn particles near agent (brief stillness)
        particles.despawnNear(agent.position, LOOP_DESPAWN_RADIUS)

        // Spawn single ambient mote (subtle sign of life)
        if (canSpawn(agent.id)) {
            val emitter = BurstEmitter()
            val config = EmitterConfig(
                type = ParticleType.MOTE,
                speed = 0.3f,
                speedVariance = 0.2f,
                life = 1.5f,
                lifeVariance = 0.3f,
                spread = 360f
            )
            particles.spawn(emitter, 1, agent.position, config)
            markSpawned(agent.id)
        }

        // Substrate: brief density dip then return to ambient
        val center = Point(agent.position.x.roundToInt(), agent.position.y.roundToInt())
        return substrateAnimator.pulse(substrate, center, intensity = -0.1f, radius = 3f)
    }

    // ── Transition helper effects ──

    private fun spawnSparkBurst(position: Vector2, count: Int) {
        val emitter = BurstEmitter()
        val config = EmitterConfig(
            type = ParticleType.SPARK,
            speed = 4f,
            speedVariance = 2f,
            life = 0.7f,
            lifeVariance = 0.2f,
            spread = 360f
        )
        particles.spawn(emitter, count, position, config)
    }

    private fun spawnTrailFromAgent(position: Vector2) {
        // Spawn trail particles radiating gently outward (executed action afterimage)
        val emitter = BurstEmitter()
        val config = EmitterConfig(
            type = ParticleType.TRAIL,
            speed = 1.5f,
            speedVariance = 0.5f,
            life = 1.2f,
            lifeVariance = 0.4f,
            spread = 360f
        )
        particles.spawn(emitter, 8, position, config)
    }

    // ── Cooldown management ──

    private fun canSpawn(agentId: String): Boolean {
        return (spawnCooldowns[agentId] ?: 0f) <= 0f
    }

    private fun markSpawned(agentId: String) {
        spawnCooldowns[agentId] = SPAWN_COOLDOWN
    }

    /**
     * Reset all tracked state. Call when starting a new scenario or clearing agents.
     */
    fun reset() {
        previousPhases.clear()
        spawnCooldowns.clear()
        time = 0f
    }
}
