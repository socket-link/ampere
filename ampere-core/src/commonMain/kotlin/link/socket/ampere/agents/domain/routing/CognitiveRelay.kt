package link.socket.ampere.agents.domain.routing

import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * CognitiveRelay: provider-agnostic LLM routing.
 *
 * Sits between [AgentLLMService][link.socket.ampere.agents.domain.reasoning.AgentLLMService]
 * and the actual LLM call, selecting the appropriate [AIConfiguration] based on
 * declarative routing rules. Every routing decision is emitted as an event for
 * observability.
 *
 * The relay evaluates rules in order against the current [RoutingContext].
 * The first matching rule determines the [AIConfiguration]. If no rule
 * matches, the agent's default configuration is used.
 */
interface CognitiveRelay {

    /** The current relay configuration. */
    val config: RelayConfig

    /**
     * Resolves the [AIConfiguration] to use for the given routing context.
     *
     * @param context The current routing context (phase, agent, task hints).
     * @param fallbackConfiguration The agent's default configuration (used if no rule matches).
     * @return The resolved AIConfiguration.
     */
    suspend fun resolve(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): AIConfiguration

    /**
     * Resolves routing and returns metadata suitable for external telemetry.
     *
     * The default implementation preserves compatibility for relays that only
     * care about the selected configuration.
     */
    suspend fun resolveWithMetadata(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): RoutingResolution = RoutingResolution(
        configuration = resolve(context, fallbackConfiguration),
        reason = "relay",
    )

    /**
     * Updates the relay configuration at runtime (hot-swap).
     *
     * @param newConfig The new relay configuration.
     */
    suspend fun updateConfig(newConfig: RelayConfig)
}

data class RoutingResolution(
    val configuration: AIConfiguration,
    val reason: String,
)
