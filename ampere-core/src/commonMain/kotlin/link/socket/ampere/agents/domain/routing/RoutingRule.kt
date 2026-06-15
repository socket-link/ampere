package link.socket.ampere.agents.domain.routing

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.satisfies
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

    /**
     * Registry-aware match. Rules that need provider descriptors (e.g.
     * [ByCapability]) consult [registry]; all other rules ignore it and defer
     * to the pure [matches]. Suspends because the registry is mutex-guarded.
     */
    suspend fun matches(
        context: RoutingContext,
        registry: ModelDescriptorRegistry?,
    ): Boolean = matches(context)

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
     * Example: `"Code Writer"` -> coding-specialized model.
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

    /**
     * Routes based on capability requirements carried by the step.
     *
     * The requirement flows from [RoutingContext.requirements]; this rule
     * matches only when the target provider's descriptor (looked up in the
     * registry) satisfies it. Operators order these local-first; the relay's
     * first-match semantics then pick the first capable provider.
     *
     * For an `availabilityGated` descriptor the rule additionally requires the
     * context's [local.LocalCapacity] snapshot to report that provider available; a
     * capable-but-unavailable local provider is skipped (see [evaluate]) so the
     * relay can fall through to the grid and emit a fallback.
     *
     * Requires the registry, so the pure [matches] always returns false.
     */
    @Serializable
    data class ByCapability(
        override val configuration: AIConfiguration,
    ) : RoutingRule {
        override fun matches(context: RoutingContext): Boolean = false

        override suspend fun matches(
            context: RoutingContext,
            registry: ModelDescriptorRegistry?,
        ): Boolean = evaluate(context, registry) is CapabilityEvaluation.Matched

        /**
         * Resolves this rule against [context] and [registry], distinguishing a
         * plain non-match from a capable model skipped by a closed availability
         * gate. The relay reads [CapabilityEvaluation.Skipped] to emit a
         * fallback while still selecting a later rule.
         *
         * The descriptor is looked up by the configuration's *model* name, so
         * the capability gate evaluates the actual model's tier — a sub-tier
         * model no longer rides in on its provider's best model (AMPR-214).
         */
        suspend fun evaluate(
            context: RoutingContext,
            registry: ModelDescriptorRegistry?,
        ): CapabilityEvaluation {
            val req = context.requirements ?: return CapabilityEvaluation.NoMatch
            val descriptor = registry?.descriptorFor(configuration.model.name)
                ?: return CapabilityEvaluation.NoMatch
            if (!descriptor.satisfies(req)) return CapabilityEvaluation.NoMatch

            if (descriptor.availabilityGated) {
                val capacity = context.localCapacity
                val available = capacity?.available == true &&
                    capacity.providerId == descriptor.providerId
                if (!available) {
                    return CapabilityEvaluation.Skipped(
                        reason = capacity?.reason ?: DEFAULT_UNAVAILABLE_REASON,
                    )
                }
            }
            return CapabilityEvaluation.Matched
        }

        companion object {
            /** Reason recorded when a gated provider is skipped without a stated cause. */
            const val DEFAULT_UNAVAILABLE_REASON: String = "local_capacity_unavailable"
        }
    }
}

/**
 * Outcome of evaluating a [RoutingRule.ByCapability] against a context.
 *
 * Separates "this provider can't serve the requirement" ([NoMatch]) from "this
 * provider could, but its availability gate is closed" ([Skipped]) so the relay
 * can emit a fallback only for the latter.
 */
sealed interface CapabilityEvaluation {

    /** The provider satisfies the requirement and any availability gate. */
    data object Matched : CapabilityEvaluation

    /** No requirement/registry, or the provider does not satisfy the requirement. */
    data object NoMatch : CapabilityEvaluation

    /**
     * The provider satisfies the requirement but its availability gate is
     * closed; [reason] explains why (surfaced as the fallback's `failureReason`).
     */
    data class Skipped(val reason: String) : CapabilityEvaluation
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
    is RoutingRule.ByCapability -> "capability:${configuration.provider.id}"
}
