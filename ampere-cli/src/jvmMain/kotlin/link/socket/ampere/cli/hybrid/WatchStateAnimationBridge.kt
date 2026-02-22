package link.socket.ampere.cli.hybrid

import link.socket.ampere.animation.agent.AgentActivityState as AnimAgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitiveChoreographer
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.emitter.CognitiveEvent
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.math.Vector3
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
 * - Phase-specific choreography via [CognitiveChoreographer]
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

    private val choreographer = CognitiveChoreographer(particles, substrateAnimator)
    val agentLayer = AgentLayer(
        width = accentColumns.maxOrNull()?.plus(10) ?: 80,
        height = height,
        orientation = AgentLayoutOrientation.CIRCULAR,
        // 3D layouts must use waveform world-space dimensions, not screen dimensions,
        // so agent positions land within the waveform surface bounds regardless of terminal size.
        worldWidth = 20f,
        worldDepth = 15f
    )

    /**
     * Flow connections between agents. Connections are auto-managed
     * when agents delegate tasks or communicate.
     */
    val flowLayer = FlowLayer(
        width = accentColumns.maxOrNull()?.plus(10) ?: 80,
        height = height
    )

    /**
     * Callback invoked when cognitive events are detected (phase transitions,
     * state changes). Used by the emitter bridge to fire visual effects.
     */
    var onCognitiveEvent: ((CognitiveEvent, Vector3) -> Unit)? = null

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

        // Sync agent layer first so agents exist for event detection and emitter firing
        syncAgentLayer(viewState)

        // Update substrate hotspots based on active agents
        var result = updateHotspotsFromAgentActivity(viewState, substrate)

        // Detect new significant events and spawn particles (needs agents for SparkReceived)
        detectNewEvents(viewState)

        // Detect agent state transitions and trigger pulses
        detectStateTransitions(viewState, result)?.let { result = it }

        // Run choreographer
        result = choreographer.update(agentLayer, result, deltaSeconds)

        // Apply ambient animation
        result = substrateAnimator.updateAmbient(result, deltaSeconds)

        // Update flow connections
        flowLayer.update(deltaSeconds)

        // Update particle physics
        particles.update(deltaSeconds)

        // Track state for next frame
        previousEventCount = viewState.recentSignificantEvents.size
        previousAgentStates = viewState.agentStates.mapValues { it.value.currentState }

        return result
    }

    /**
     * Sync the internal AgentLayer from WatchViewState agent states,
     * mapping CLI AgentState to animation CognitivePhase.
     *
     * Assigns 3D positions: X uses horizontal distribution, Z-depth is
     * assigned based on creation order, Y (height) is left at 0 for the
     * waveform to control.
     */
    private fun syncAgentLayer(viewState: WatchViewState) {
        val currentIds = viewState.agentStates.keys
        val existingIds = agentLayer.allAgents.map { it.id }.toSet()

        // Remove agents no longer present
        for (id in existingIds - currentIds) {
            agentLayer.removeAgent(id)
            // Clean up flow connections involving removed agents
            flowLayer.allConnections
                .filter { it.sourceAgentId == id || it.targetAgentId == id }
                .forEach { flowLayer.removeConnection(it.id) }
        }

        // Add or update agents
        val agentEntries = viewState.agentStates.entries.toList()

        for (entry in agentEntries) {
            val (id, activityState) = entry
            val phase = mapAgentStateToCognitivePhase(activityState.currentState)
            val animState = mapAgentStateToActivityState(activityState.currentState)

            if (id in existingIds) {
                // Detect phase transitions and fire cognitive events
                val existingAgent = agentLayer.getAgent(id)
                if (existingAgent != null && existingAgent.cognitivePhase != phase) {
                    val agentPos = existingAgent.position3D
                    onCognitiveEvent?.invoke(
                        CognitiveEvent.PhaseTransition(id, existingAgent.cognitivePhase, phase),
                        agentPos
                    )
                }
                agentLayer.updateAgentState(id, animState)
                agentLayer.updateAgentCognitivePhase(id, phase)
            } else {
                // New agent — CIRCULAR layout will assign 3D positions via relayout()
                agentLayer.addAgent(
                    AgentVisualState(
                        id = id,
                        name = activityState.displayName,
                        role = "",
                        position = Vector2.ZERO,
                        state = animState,
                        cognitivePhase = phase,
                    )
                )
            }
        }

        // Auto-manage flow connections between active agents
        syncFlowConnections()
    }

    /**
     * Create flow connections between active agents to visualize
     * inter-agent communication as ridges on the waveform surface.
     * Agents in EXECUTE or PROCESSING states get connected to each other.
     */
    private fun syncFlowConnections() {
        val activeAgents = agentLayer.allAgents.filter {
            it.state == AnimAgentActivityState.ACTIVE ||
            it.state == AnimAgentActivityState.PROCESSING
        }

        // Create connections between adjacent active agents (chain topology)
        val existingConnectionIds = flowLayer.allConnections.map { it.id }.toSet()
        val desiredConnectionIds = mutableSetOf<String>()

        for (i in 0 until activeAgents.size - 1) {
            val source = activeAgents[i]
            val target = activeAgents[i + 1]
            val connectionId = "${source.id}->${target.id}"
            desiredConnectionIds.add(connectionId)

            if (connectionId !in existingConnectionIds) {
                flowLayer.createConnection(
                    sourceAgentId = source.id,
                    targetAgentId = target.id,
                    sourcePosition = source.position,
                    targetPosition = target.position
                )
            }
        }

        // Remove stale connections
        for (connection in flowLayer.allConnections) {
            if (connection.id !in desiredConnectionIds) {
                flowLayer.removeConnection(connection.id)
            }
        }
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

            // Fire spark events on agents for waveform emitter effects
            for (agent in agentLayer.allAgents.take(2)) {
                onCognitiveEvent?.invoke(
                    CognitiveEvent.SparkReceived(agent.id),
                    agent.position3D
                )
            }
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

            // Fire TaskCompleted when agent finishes (WORKING → IDLE)
            if (previousState == AgentState.WORKING && currentState == AgentState.IDLE) {
                val agentPos = agentLayer.getAgent(agentId)?.position3D ?: Vector3.ZERO
                onCognitiveEvent?.invoke(CognitiveEvent.TaskCompleted(agentId), agentPos)
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

    companion object {
        /**
         * Map CLI AgentState to animation CognitivePhase.
         */
        fun mapAgentStateToCognitivePhase(state: AgentState): CognitivePhase {
            return when (state) {
                AgentState.THINKING -> CognitivePhase.PLAN
                AgentState.WORKING -> CognitivePhase.EXECUTE
                AgentState.IN_MEETING -> CognitivePhase.RECALL
                AgentState.WAITING -> CognitivePhase.PERCEIVE
                AgentState.IDLE -> CognitivePhase.NONE
            }
        }

        /**
         * Map CLI AgentState to animation AgentActivityState.
         */
        fun mapAgentStateToActivityState(state: AgentState): AnimAgentActivityState {
            return when (state) {
                AgentState.THINKING -> AnimAgentActivityState.PROCESSING
                AgentState.WORKING -> AnimAgentActivityState.ACTIVE
                AgentState.IN_MEETING -> AnimAgentActivityState.PROCESSING
                AgentState.WAITING -> AnimAgentActivityState.IDLE
                AgentState.IDLE -> AnimAgentActivityState.IDLE
            }
        }
    }
}
