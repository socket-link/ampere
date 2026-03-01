package link.socket.ampere.cli.render

import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.cli.watch.presentation.ProviderCallTelemetrySummary
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.emitter.MetadataKeys
import link.socket.phosphor.math.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmperePhosphorBridgeTest {
    @Test
    fun `provider telemetry emits metadata rich effect`() {
        val emitterManager = EmitterManager()
        val bridge = AmperePhosphorBridge(emitterManager)
        val telemetry = ProviderCallTelemetrySummary(
            eventId = "evt-provider-1",
            agentId = "agent-1",
            cognitivePhase = CognitivePhase.EXECUTE,
            latencyMs = 1_200,
            estimatedCost = 0.015,
            totalTokens = 6_000,
            success = true
        )

        bridge.onProviderCallCompleted(telemetry, Vector3(2f, 0f, 3f), currentTime = 1.5f)

        assertEquals(1, emitterManager.activeCount)
        val instance = emitterManager.instances.single()
        assertEquals(1.5f, instance.activatedAt)
        assertEquals(Vector3(2f, 0f, 3f), instance.position)
        assertTrue(instance.metadata.containsKey(MetadataKeys.HEAT))
        assertTrue(instance.metadata.containsKey(MetadataKeys.INTENSITY))
        assertTrue(instance.metadata.containsKey(MetadataKeys.DENSITY))
        assertEquals(
            CostNormalizer.normalizeCost(0.015),
            instance.metadata.getValue(MetadataKeys.HEAT)
        )
        assertEquals(
            CostNormalizer.normalizeLatency(1_200),
            instance.metadata.getValue(MetadataKeys.INTENSITY)
        )
        assertEquals(
            CostNormalizer.normalizeTokens(6_000),
            instance.metadata.getValue(MetadataKeys.DENSITY)
        )
    }

    @Test
    fun `provider telemetry without cost still emits latency metadata`() {
        val emitterManager = EmitterManager()
        val bridge = AmperePhosphorBridge(emitterManager)
        val telemetry = ProviderCallTelemetrySummary(
            eventId = "evt-provider-2",
            agentId = "agent-2",
            cognitivePhase = null,
            latencyMs = 250,
            estimatedCost = null,
            totalTokens = null,
            success = false
        )

        bridge.onProviderCallCompleted(telemetry, Vector3.ZERO)

        val instance = emitterManager.instances.single()
        assertEquals(setOf(MetadataKeys.INTENSITY), instance.metadata.keys)
        assertEquals(
            CostNormalizer.normalizeLatency(250),
            instance.metadata.getValue(MetadataKeys.INTENSITY)
        )
    }
}
