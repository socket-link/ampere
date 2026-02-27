package link.socket.ampere.agents.domain.routing

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed

/**
 * A declarative routing rule that matches a [RoutingContext] to an [AIConfiguration].
 *
 * Rules are evaluated in order; the first match wins. If no rule matches,
 * the relay falls back to the agent's default AIConfiguration.
 */
@Serializable
sealed interface RoutingRule {

    /** Returns true if this rule applies to the given context. */
    fun matches(context: RoutingContext): Boolean

    /** The AIConfiguration to use when this rule matches. */
    val configuration: AIConfiguration

    /**
     * Routes based on the current cognitive phase.
     *
     * Example: PERCEIVE -> fast/cheap model, EXECUTE -> powerful model.
     */
    @Serializable
    data class ByPhase(
        val phase: CognitivePhase,
        override val configuration: AIConfiguration,
    ) : RoutingRule {
        override fun matches(context: RoutingContext): Boolean =
            context.phase == phase
    }

    /**
     * Routes based on the agent's identity.
     *
     * Example: specific agent instance -> dedicated model.
     */
    @Serializable
    data class ByAgent(
        val agentId: AgentId,
        override val configuration: AIConfiguration,
    ) : RoutingRule {
        override fun matches(context: RoutingContext): Boolean =
            context.agentId == agentId
    }

    /**
     * Routes based on the agent's role name.
     *
     * Example: "CodeAgent" -> coding-specialized model.
     */
    @Serializable
    data class ByRole(
        val roleName: String,
        override val configuration: AIConfiguration,
    ) : RoutingRule {
        override fun matches(context: RoutingContext): Boolean =
            context.agentRole == roleName
    }

    /**
     * Routes based on desired model features (speed/reasoning).
     *
     * Example: HIGH reasoning -> o1-class model.
     */
    @Serializable
    data class ByFeatures(
        val reasoning: RelativeReasoning? = null,
        val speed: RelativeSpeed? = null,
        override val configuration: AIConfiguration,
    ) : RoutingRule {
        override fun matches(context: RoutingContext): Boolean {
            if (reasoning != null && context.preferredReasoning != reasoning) return false
            if (speed != null && context.preferredSpeed != speed) return false
            return reasoning != null || speed != null
        }
    }

    /**
     * Routes based on tags present in the routing context.
     *
     * Example: tag "code-generation" -> Claude Sonnet 4.5.
     */
    @Serializable
    data class ByTag(
        val tag: String,
        override val configuration: AIConfiguration,
    ) : RoutingRule {
        override fun matches(context: RoutingContext): Boolean =
            tag in context.tags
    }
}

/**
 * Returns a human-readable description of this routing rule for event summaries.
 */
fun RoutingRule.describeRule(): String = when (this) {
    is RoutingRule.ByPhase -> "phase:${phase.name}"
    is RoutingRule.ByAgent -> "agent:$agentId"
    is RoutingRule.ByRole -> "role:$roleName"
    is RoutingRule.ByFeatures -> buildString {
        append("features:")
        reasoning?.let { append("reasoning=$it") }
        if (reasoning != null && speed != null) append(",")
        speed?.let { append("speed=$it") }
    }
    is RoutingRule.ByTag -> "tag:$tag"
}
