package link.socket.ampere.agents.domain.routing

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * Declarative configuration for the [CognitiveRelay].
 *
 * Contains an ordered list of routing rules and an optional default configuration.
 * Rules are evaluated in order; the first match determines the route.
 *
 * @property rules Ordered list of routing rules. First match wins.
 * @property defaultConfiguration Used when no rule matches. Falls back to agent's
 *   own AIConfiguration if this is also null.
 */
@Serializable
data class RelayConfig(
    val rules: List<RoutingRule> = emptyList(),
    val defaultConfiguration: AIConfiguration? = null,
)
