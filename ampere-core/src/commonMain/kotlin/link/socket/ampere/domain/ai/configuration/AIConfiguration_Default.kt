@file:Suppress("ktlint:standard:class-naming")

package link.socket.ampere.domain.ai.configuration

import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter
import link.socket.ampere.domain.ai.provider.OpenRouterModelSlugs

data class AIConfiguration_Default(
    override val provider: AIProvider<*, *>,
    override val model: AIModel,
) : AIConfiguration {

    /**
     * Routed through OpenRouter, the model is sent as OpenRouter's slug; a model
     * with none is refused rather than sent under its maker's id, which OpenRouter
     * would not recognise.
     */
    override val wireModelId: String
        get() = if (provider.id == AIProvider_OpenRouter.id) {
            requireNotNull(OpenRouterModelSlugs.slugFor(model)) {
                "OpenRouter has no slug for model '${model.name}'"
            }
        } else {
            model.name
        }

    override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> =
        listOf(provider to model)
}
