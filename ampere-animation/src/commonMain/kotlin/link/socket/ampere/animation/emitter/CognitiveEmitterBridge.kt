package link.socket.ampere.animation.emitter

import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp

/**
 * Cognitive events that trigger visual effects.
 * These are consumed by the emitter bridge, not emitted by it.
 */
sealed class CognitiveEvent {
    data class SparkReceived(val agentId: String) : CognitiveEvent()
    data class PhaseTransition(
        val agentId: String,
        val oldPhase: CognitivePhase,
        val newPhase: CognitivePhase
    ) : CognitiveEvent()
    data class UncertaintySpike(val agentId: String, val level: Float) : CognitiveEvent()
    data class TaskCompleted(val agentId: String) : CognitiveEvent()
    data class HumanEscalation(val agentId: String) : CognitiveEvent()
}

/**
 * Maps cognitive events to emitter effects — the translation table between
 * "what the brain did" and "what it looks like."
 *
 * This is where the visual identity of each cognitive event is defined.
 * Change these mappings and the entire feel of the visualization shifts.
 */
class CognitiveEmitterBridge(
    private val emitterManager: EmitterManager
) {
    /**
     * React to a cognitive event by firing appropriate emitters.
     */
    fun onCognitiveEvent(event: CognitiveEvent, agentPosition: Vector3) {
        when (event) {
            is CognitiveEvent.SparkReceived -> {
                emitterManager.emit(
                    EmitterEffect.SparkBurst(
                        duration = 3f,
                        radius = 8f,
                        ringWidth = 2f,
                        expansionSpeed = 2.5f
                    ),
                    agentPosition
                )
                emitterManager.emit(
                    EmitterEffect.HeightPulse(
                        duration = 3f,
                        radius = 6f,
                        maxHeightBoost = 4f
                    ),
                    agentPosition
                )
            }
            is CognitiveEvent.PhaseTransition -> {
                emitterManager.emit(
                    EmitterEffect.ColorWash(
                        duration = 3f,
                        radius = 12f,
                        colorRamp = CognitiveColorRamp.forPhase(event.newPhase),
                        waveFrontSpeed = 3f
                    ),
                    agentPosition
                )
            }
            is CognitiveEvent.UncertaintySpike -> {
                emitterManager.emit(
                    EmitterEffect.Turbulence(
                        duration = 3f,
                        radius = 8f,
                        noiseAmplitude = 1.5f * event.level.coerceIn(0f, 1f)
                    ),
                    agentPosition
                )
            }
            is CognitiveEvent.TaskCompleted -> {
                emitterManager.emit(
                    EmitterEffect.Confetti(
                        duration = 2f,
                        radius = 6f
                    ),
                    agentPosition
                )
            }
            is CognitiveEvent.HumanEscalation -> {
                emitterManager.emit(
                    EmitterEffect.SparkBurst(
                        duration = 4f,
                        radius = 12f,
                        ringWidth = 3f,
                        expansionSpeed = 2f,
                        palette = AsciiLuminancePalette.EXECUTE
                    ),
                    agentPosition
                )
            }
        }
    }
}
