package link.socket.ampere.domain.ai.configuration

import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

data class AIConfiguration_Default(
    override val provider: AIProvider<*, *>,
    override val model: AIModel,
) : AIConfiguration {

    override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> =
        listOf(provider to model)
}
