package link.socket.ampere.cli.render

import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase as AmperePhase
import link.socket.ampere.cli.watch.presentation.ProviderCallTelemetrySummary
import link.socket.phosphor.bridge.CognitiveEmitterBridge
import link.socket.phosphor.bridge.CognitiveEvent
import link.socket.phosphor.emitter.EmitterEffect
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.emitter.MetadataKeys
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.AsciiLuminancePalette
import link.socket.phosphor.palette.CognitiveColorRamp
import link.socket.phosphor.signal.CognitivePhase as PhosphorPhase

/**
 * Preserves the existing cognitive emitter choreography and adds Ampere-specific
 * provider-call metadata channels for Phosphor 0.3.0.
 */
class AmperePhosphorBridge(
    private val emitterManager: EmitterManager,
    private val cognitiveEmitterBridge: CognitiveEmitterBridge = CognitiveEmitterBridge(emitterManager)
) {
    fun onCognitiveEvent(event: CognitiveEvent, agentPosition: Vector3) {
        cognitiveEmitterBridge.onCognitiveEvent(event, agentPosition)
    }

    fun onProviderCallCompleted(
        event: ProviderCallTelemetrySummary,
        agentPosition: Vector3,
        currentTime: Float = 0f
    ) {
        emitterManager.emit(
            effect = effectForPhase(event.cognitivePhase),
            position = agentPosition,
            currentTime = currentTime,
            metadata = metadataFor(event)
        )
    }

    internal fun metadataFor(event: ProviderCallTelemetrySummary): Map<String, Float> = buildMap {
        event.estimatedCost?.let { put(MetadataKeys.HEAT, CostNormalizer.normalizeCost(it)) }
        event.totalTokens?.let { put(MetadataKeys.DENSITY, CostNormalizer.normalizeTokens(it)) }
        put(MetadataKeys.INTENSITY, CostNormalizer.normalizeLatency(event.latencyMs))
    }

    private fun effectForPhase(phase: AmperePhase?): EmitterEffect = when (phase) {
        AmperePhase.PERCEIVE -> EmitterEffect.HeightPulse()
        AmperePhase.PLAN -> EmitterEffect.ColorWash(
            colorRamp = CognitiveColorRamp.forPhase(PhosphorPhase.PLAN)
        )
        AmperePhase.EXECUTE -> EmitterEffect.SparkBurst(
            palette = AsciiLuminancePalette.EXECUTE
        )
        AmperePhase.LEARN -> EmitterEffect.ColorWash(
            colorRamp = CognitiveColorRamp.forPhase(PhosphorPhase.EVALUATE)
        )
        null -> EmitterEffect.HeightPulse()
    }
}
