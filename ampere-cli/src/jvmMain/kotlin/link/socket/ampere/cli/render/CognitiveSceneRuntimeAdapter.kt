package link.socket.ampere.cli.render

import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.field.FlowState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.render.Camera
import link.socket.phosphor.render.CognitiveWaveform
import link.socket.phosphor.runtime.CameraOrbitConfiguration
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.runtime.WaveformConfiguration

/**
 * Bridges Ampere's existing watch-state driven layers onto Phosphor's unified
 * scene runtime so rendering consumes a single runtime update contract.
 */
class CognitiveSceneRuntimeAdapter {
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedCoordinateSpace: CoordinateSpace? = null
    private var runtime: CognitiveSceneRuntime? = null
    private var latestSnapshot: SceneSnapshot? = null

    val emitterManager: EmitterManager?
        get() = runtime?.emitters

    val waveform: CognitiveWaveform?
        get() = runtime?.waveform

    val agents: AgentLayer?
        get() = runtime?.agents

    fun update(
        width: Int,
        height: Int,
        deltaSeconds: Float,
        sourceAgents: AgentLayer,
        sourceFlow: FlowLayer?
    ): SceneSnapshot {
        val activeRuntime = ensureRuntime(width, height, sourceAgents.coordinateSpace)
        syncAgents(sourceAgents, activeRuntime.agents)
        activeRuntime.flow?.let { syncFlow(sourceFlow, it) }
        return activeRuntime.update(deltaSeconds).also { latestSnapshot = it }
    }

    fun currentCamera(): Camera? = runtime?.cameraOrbit?.currentCamera()

    fun snapshot(): SceneSnapshot? = latestSnapshot ?: runtime?.snapshot()

    private fun ensureRuntime(
        width: Int,
        height: Int,
        coordinateSpace: CoordinateSpace
    ): CognitiveSceneRuntime {
        val needsRebuild = runtime == null ||
            width != cachedWidth ||
            height != cachedHeight ||
            coordinateSpace != cachedCoordinateSpace

        if (needsRebuild) {
            cachedWidth = width
            cachedHeight = height
            cachedCoordinateSpace = coordinateSpace
            runtime = createRuntime(width, height, coordinateSpace)
            latestSnapshot = runtime?.snapshot()
        }

        return requireNotNull(runtime)
    }

    private fun createRuntime(
        width: Int,
        height: Int,
        coordinateSpace: CoordinateSpace
    ): CognitiveSceneRuntime {
        val config = SceneConfiguration(
            width = width,
            height = height,
            agents = emptyList(),
            initialConnections = emptyList(),
            enableWaveform = true,
            enableParticles = false,
            enableFlow = true,
            enableEmitters = true,
            enableCamera = true,
            coordinateSpace = coordinateSpace,
            seed = 0xA63E0L,
            agentLayout = AgentLayoutOrientation.CUSTOM,
            waveform = WaveformConfiguration(
                gridWidth = width.coerceAtMost(60),
                gridDepth = height.coerceAtMost(40),
                worldWidth = 20f,
                worldDepth = 15f
            ),
            cameraOrbit = CameraOrbitConfiguration(
                radius = 15f,
                height = 8f,
                orbitSpeed = 0.08f,
                wobbleAmplitude = 0.3f,
                wobbleFrequency = 0.2f
            )
        )
        return CognitiveSceneRuntime(config)
    }

    private fun syncAgents(source: AgentLayer, target: AgentLayer) {
        val sourceById = source.allAgents.associateBy { it.id }
        val targetIds = target.allAgents.map { it.id }.toSet()

        for (id in targetIds - sourceById.keys) {
            target.removeAgent(id)
        }

        for ((id, agent) in sourceById) {
            if (target.getAgent(id) == null) {
                target.addAgent(agent.copy())
                continue
            }

            target.setAgentPosition(id, agent.position)
            target.setAgentPosition3D(id, agent.position3D)
            target.updateAgentState(id, agent.state)
            target.updateAgentStatus(id, agent.statusText)
            target.updateAgentCognitivePhase(id, agent.cognitivePhase, agent.phaseProgress)
        }
    }

    private fun syncFlow(source: FlowLayer?, target: FlowLayer) {
        if (source == null) {
            if (target.connectionCount > 0) {
                target.clear()
            }
            return
        }

        val sourceById = source.allConnections.associateBy { it.id }
        val targetConnections = target.allConnections

        for (connection in targetConnections) {
            if (connection.id !in sourceById) {
                target.removeConnection(connection.id)
            }
        }

        for (connection in source.allConnections) {
            val sourcePos = connection.path.firstOrNull() ?: Vector2.ZERO
            val targetPos = connection.path.lastOrNull() ?: sourcePos
            val existing = target.getConnection(connection.id)
            val resolvedId = existing?.id ?: target.createConnection(
                sourceAgentId = connection.sourceAgentId,
                targetAgentId = connection.targetAgentId,
                sourcePosition = sourcePos,
                targetPosition = targetPos
            )

            target.updateConnectionPath(resolvedId, sourcePos, targetPos)

            if (connection.state == FlowState.TRANSMITTING) {
                target.startHandoff(resolvedId)
            }
        }
    }
}
