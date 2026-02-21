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
                emitterManager.emit(EmitterEffect.SparkBurst(), agentPosition)
                emitterManager.emit(EmitterEffect.HeightPulse(), agentPosition)
            }
            is CognitiveEvent.PhaseTransition -> {
                emitterManager.emit(
                    EmitterEffect.ColorWash(
                        colorRamp = CognitiveColorRamp.forPhase(event.newPhase)
                    ),
                    agentPosition
                )
            }
            is CognitiveEvent.UncertaintySpike -> {
                emitterManager.emit(
                    EmitterEffect.Turbulence(
                        noiseAmplitude = 1.5f * event.level.coerceIn(0f, 1f)
                    ),
                    agentPosition
                )
            }
            is CognitiveEvent.TaskCompleted -> {
                emitterManager.emit(EmitterEffect.Confetti(), agentPosition)
            }
            is CognitiveEvent.HumanEscalation -> {
                emitterManager.emit(
                    EmitterEffect.SparkBurst(
                        duration = 2f,
                        radius = 10f,
                        palette = AsciiLuminancePalette.EXECUTE
                    ),
                    agentPosition
                )
            }
        }
    }
}
