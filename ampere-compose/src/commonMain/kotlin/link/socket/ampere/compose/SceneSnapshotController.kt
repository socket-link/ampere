package link.socket.ampere.compose

import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.emitter.EmitterEffect
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.CognitiveColorRamp
import link.socket.phosphor.render.Camera
import link.socket.phosphor.render.CognitiveWaveform
import link.socket.phosphor.runtime.AgentDescriptor
import link.socket.phosphor.runtime.CameraOrbitConfiguration
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.FlowConnectionDescriptor
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.runtime.WaveformConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Shared snapshot-driven runtime controller for mobile wrapper surfaces.
 */
class SceneSnapshotController(
    configuration: SceneConfiguration = defaultSceneConfiguration(),
    private val phaseStepSeconds: Float = 1.8f
) {
    private val runtime = CognitiveSceneRuntime(configuration)
    private val phaseCycle = listOf(
        CognitivePhase.PERCEIVE,
        CognitivePhase.RECALL,
        CognitivePhase.PLAN,
        CognitivePhase.EXECUTE,
        CognitivePhase.EVALUATE,
        CognitivePhase.LOOP
    )
    private var phaseIndex = 0
    private var phaseElapsed = 0f

    val particles: ParticleSystem
        get() = runtime.particles ?: error("SceneConfiguration must enable particles")

    val agentLayer: AgentLayer
        get() = runtime.agents

    val flow: FlowLayer?
        get() = runtime.flow

    val waveform: CognitiveWaveform?
        get() = runtime.waveform

    val camera: Camera?
        get() = runtime.cameraOrbit?.currentCamera()

    fun snapshot(): SceneSnapshot = runtime.snapshot()

    fun update(deltaSeconds: Float): SceneSnapshot {
        phaseElapsed += deltaSeconds
        if (phaseElapsed >= phaseStepSeconds) {
            phaseElapsed -= phaseStepSeconds
            advanceDemoPhase()
        }
        return runtime.update(deltaSeconds)
    }

    private fun advanceDemoPhase() {
        val agents = runtime.agents
        if (agents.agentCount == 0) return

        phaseIndex = (phaseIndex + 1) % phaseCycle.size
        val activePhase = phaseCycle[phaseIndex]

        for ((offset, agent) in agents.allAgents.withIndex()) {
            val phase = phaseCycle[(phaseIndex + offset) % phaseCycle.size]
            val state = when (phase) {
                CognitivePhase.EXECUTE -> AgentActivityState.ACTIVE
                CognitivePhase.LOOP -> AgentActivityState.IDLE
                CognitivePhase.NONE -> AgentActivityState.IDLE
                else -> AgentActivityState.PROCESSING
            }

            agents.updateAgentState(agent.id, state)
            agents.updateAgentCognitivePhase(agent.id, phase)
        }

        val source = agents.allAgents.firstOrNull() ?: return
        runtime.emit(
            effect = when (activePhase) {
                CognitivePhase.EXECUTE -> EmitterEffect.SparkBurst()
                CognitivePhase.PLAN -> EmitterEffect.ColorWash(
                    colorRamp = CognitiveColorRamp.forPhase(CognitivePhase.PLAN)
                )
                else -> EmitterEffect.HeightPulse()
            },
            position = source.position3D,
            metadata = emptyMap<String, Float>()
        )
    }

    companion object {
        fun defaultSceneConfiguration(
            width: Int = 80,
            height: Int = 34
        ): SceneConfiguration {
            return SceneConfiguration(
                width = width,
                height = height,
                agents = listOf(
                    AgentDescriptor(
                        id = "spark",
                        name = "Spark",
                        role = "reasoning",
                        position = Vector2.ZERO,
                        position3D = Vector3(-4f, 0f, -2f),
                        state = AgentActivityState.ACTIVE,
                        statusText = "Perceiving",
                        cognitivePhase = CognitivePhase.PERCEIVE,
                        phaseProgress = 0f
                    ),
                    AgentDescriptor(
                        id = "memory",
                        name = "Memory",
                        role = "memory",
                        position = Vector2.ZERO,
                        position3D = Vector3(4f, 0f, 2f),
                        state = AgentActivityState.PROCESSING,
                        statusText = "Recalling",
                        cognitivePhase = CognitivePhase.RECALL,
                        phaseProgress = 0f
                    ),
                    AgentDescriptor(
                        id = "coordinator",
                        name = "Coordinator",
                        role = "coordinator",
                        position = Vector2.ZERO,
                        position3D = Vector3(0f, 0f, 5f),
                        state = AgentActivityState.PROCESSING,
                        statusText = "Planning",
                        cognitivePhase = CognitivePhase.PLAN,
                        phaseProgress = 0f
                    )
                ),
                initialConnections = listOf(
                    FlowConnectionDescriptor(
                        sourceAgentId = "spark",
                        targetAgentId = "memory",
                        startHandoff = true
                    ),
                    FlowConnectionDescriptor(
                        sourceAgentId = "memory",
                        targetAgentId = "coordinator",
                        startHandoff = true
                    )
                ),
                enableWaveform = true,
                enableParticles = true,
                enableFlow = true,
                enableEmitters = true,
                enableCamera = true,
                seed = 0xA63E0L,
                agentLayout = AgentLayoutOrientation.CIRCULAR,
                waveform = WaveformConfiguration(
                    gridWidth = 56,
                    gridDepth = 32,
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
        }
    }
}
