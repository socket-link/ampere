package link.socket.ampere.agents.domain.routing

import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * Default [RoutingRule.ByCapability] set over the bundled cloud catalog.
 *
 * Activating the relay in a production agent path (AMPR-219) needs a real,
 * non-empty rule list: with no capability rules a declared rung floor can never
 * be met (the relay has nothing to satisfy it with) and every call would fail
 * [RoutingResolution.FloorUnmet]. These rules give the relay one
 * [RoutingRule.ByCapability] per bundled cloud model so cost-aware selection
 * (AMPR-210) can pick the cheapest model that clears the floor.
 *
 * The model lists are pulled from the same `AIModel.*.ALL_MODELS` companions the
 * [InMemoryModelDescriptorRegistry] seeds from, so rules and descriptors stay in
 * lockstep — every rule's model has a descriptor (with a rung) to evaluate
 * against. Rule order is irrelevant to the winner: the first capability match
 * only flips the relay into cost-aware mode, after which the cheapest capable
 * candidate wins regardless of position.
 */
object CapabilityRoutingDefaults {

    /**
     * One [RoutingRule.ByCapability] per bundled cloud model, across the three
     * bundled providers. Pair this with an [InMemoryModelDescriptorRegistry]
     * (its default seed) so each rule's model resolves to a descriptor.
     */
    fun cloudCapabilityRules(): List<RoutingRule.ByCapability> =
        listOf(
            Pair(AIProvider_Google, AIModel_Gemini.ALL_MODELS),
            Pair(AIProvider_Anthropic, AIModel_Claude.ALL_MODELS),
            Pair(AIProvider_OpenAI, AIModel_OpenAI.ALL_MODELS),
        ).flatMap { (provider, models) ->
            models.map { model ->
                RoutingRule.ByCapability(AIConfiguration_Default(provider, model))
            }
        }
}
