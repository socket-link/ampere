package link.socket.ampere.domain.ai.configuration

import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

interface AIConfiguration {
    val provider: AIProvider<*, *>
    val model: AIModel

    /**
     * The model id sent on the wire. A configuration that reaches [model] through its
     * maker sends the maker's id, [AIModel.name]; one routed through a gateway sends
     * the gateway's id for the same model (AMPR-434).
     */
    val wireModelId: String
        get() = model.name

    // TODO: Change return type into data class
    fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>>
}
