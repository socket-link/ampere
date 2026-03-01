package link.socket.ampere.agents.domain.routing

import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * A passthrough relay that always returns the fallback configuration.
 *
 * Used when no routing rules are configured, preserving existing behavior
 * with zero overhead.
 */
object CognitiveRelayPassthrough : CognitiveRelay {

    override val config: RelayConfig = RelayConfig()

    override suspend fun resolve(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): AIConfiguration = fallbackConfiguration

    override suspend fun resolveWithMetadata(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): RoutingResolution = RoutingResolution(
        configuration = fallbackConfiguration,
        reason = "passthrough",
    )

    override suspend fun updateConfig(newConfig: RelayConfig) {
        // No-op: passthrough does not support reconfiguration.
    }
}
