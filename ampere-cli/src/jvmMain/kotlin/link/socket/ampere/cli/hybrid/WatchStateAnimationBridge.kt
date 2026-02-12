package link.socket.ampere.cli.hybrid

import link.socket.ampere.animation.particle.BurstEmitter
import link.socket.ampere.animation.particle.EmitterConfig
import link.socket.ampere.animation.particle.ParticleSystem
import link.socket.ampere.animation.particle.ParticleType
import link.socket.ampere.animation.substrate.Point
import link.socket.ampere.animation.substrate.SubstrateAnimator
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchViewState

/**
 * Bridges WatchViewState into animation model updates.
 *
 * Converts agent activity, events, and system state into:
 * - Substrate density hotspots (near active agents)
 * - Particle bursts (on significant events)
 *
 * All effects are subtle so they enhance pane content rather than competing with it.
 *
 * @property substrateAnimator The animator for substrate density effects
 * @property particles The particle system for event accents
 * @property accentColumns The column positions where animation is visible (dividers, margins)
 * @property height The buffer height for positioning effects
 */
class WatchStateAnimationBridge(
    private val substrateAnimator: SubstrateAnimator,
    private val particles: ParticleSystem,
    private val accentColumns: Set<Int>,
    private val height: Int,
    private val maxParticles: Int = 30
) {
    private var previousEventCount = 0
    private var previousAgentStates = mapOf<String, AgentState>()
    private val burstEmitter = BurstEmitter()

    /**
     * Update animation state based on current watch state.
     *
     * @param viewState Current snapshot from WatchPresenter (null-safe for graceful degradation)
     * @param substrate Current substrate state
     * @param deltaSeconds Time since last frame
     * @return Updated substrate state
     */
    fun update(
        viewState: WatchViewState?,
        substrate: SubstrateState,
        deltaSeconds: Float
    ): SubstrateState {
        if (viewState == null) {
            return substrateAnimator.updateAmbient(substrate, deltaSeconds)
        }

        // Update substrate hotspots based on active agents
        var result = updateHotspotsFromAgentActivity(viewState, substrate)

        // Detect new significant events and spawn particles
        detectNewEvents(viewState)

        // Detect agent state transitions and trigger pulses
        detectStateTransitions(viewState, result)?.let { result = it }

        // Apply ambient animation
        result = substrateAnimator.updateAmbient(result, deltaSeconds)

        // Update particle physics
        particles.update(deltaSeconds)

        // Track state for next frame
        previousEventCount = viewState.recentSignificantEvents.size
        previousAgentStates = viewState.agentStates.mapValues { it.value.currentState }

        return result
    }

    private fun updateHotspotsFromAgentActivity(
        viewState: WatchViewState,
        substrate: SubstrateState
    ): SubstrateState {
        val activeCount = viewState.agentStates.count { !it.value.isIdle }
        if (activeCount == 0) return substrate

        // Place hotspots at accent column positions, distributed vertically
        val hotspots = accentColumns.flatMapIndexed { idx, col ->
            if (idx < activeCount) {
                val y = (height * (idx + 1)) / (activeCount + 1)
                listOf(Point(col, y.coerceIn(0, substrate.height - 1)))
            } else {
                emptyList()
            }
        }

        return substrate.withHotspots(hotspots)
    }

    private fun detectNewEvents(viewState: WatchViewState) {
        val currentCount = viewState.recentSignificantEvents.size
        if (currentCount <= previousEventCount) return

        // New events arrived - check significance
        val newEvents = viewState.recentSignificantEvents.take(currentCount - previousEventCount)
        val hasSignificant = newEvents.any {
            it.significance == EventSignificance.CRITICAL || it.significance == EventSignificance.SIGNIFICANT
        }

        if (hasSignificant && particles.count < maxParticles) {
            spawnEventParticles(
                if (newEvents.any { it.significance == EventSignificance.CRITICAL }) 3 else 2
            )
        }
    }

    private fun detectStateTransitions(
        viewState: WatchViewState,
        substrate: SubstrateState
    ): SubstrateState? {
        var result: SubstrateState? = null

        for ((agentId, agentState) in viewState.agentStates) {
            val previousState = previousAgentStates[agentId]
            val currentState = agentState.currentState

            // Trigger a pulse when an agent transitions to WORKING or THINKING
            if (previousState != null && previousState != currentState &&
                (currentState == AgentState.WORKING || currentState == AgentState.THINKING)
            ) {
                val pulseCenter = pickAccentPosition()
                result = substrateAnimator.pulse(
                    result ?: substrate,
                    pulseCenter,
                    intensity = 0.3f,
                    radius = 3f
                )
            }
        }

        return result
    }

    private fun spawnEventParticles(count: Int) {
        val origin = pickAccentPosition()
        val config = EmitterConfig(
            type = ParticleType.SPARK,
            speed = 1.5f,
            speedVariance = 0.5f,
            life = 1.2f,
            lifeVariance = 0.3f,
            spread = 360f
        )
        particles.spawn(burstEmitter, count, origin.toVector2(), config)
    }

    private fun pickAccentPosition(): Point {
        val col = accentColumns.randomOrNull() ?: 0
        val row = (1 until height.coerceAtLeast(2)).random()
        return Point(col, row)
    }
}
