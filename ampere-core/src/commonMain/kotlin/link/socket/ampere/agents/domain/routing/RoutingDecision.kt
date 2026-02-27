package link.socket.ampere.agents.domain.routing

import kotlinx.serialization.Serializable

/**
 * The result of a routing evaluation — which model was selected and why.
 *
 * @property providerName The name of the provider selected (e.g., "Anthropic").
 * @property modelName The name of the model selected (e.g., "claude-sonnet-4-0").
 * @property matchedRule Which rule matched, or "default" if no rule matched.
 * @property isFallback Whether this was a fallback from the primary choice.
 */
@Serializable
data class RoutingDecision(
    val providerName: String,
    val modelName: String,
    val matchedRule: String,
    val isFallback: Boolean = false,
)
