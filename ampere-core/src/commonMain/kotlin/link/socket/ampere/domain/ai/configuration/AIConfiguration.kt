package link.socket.ampere.domain.ai.configuration

import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

interface AIConfiguration {
    val provider: AIProvider<*, *>
    val model: AIModel

    // TODO: Change return type into data class
    fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>>
}
