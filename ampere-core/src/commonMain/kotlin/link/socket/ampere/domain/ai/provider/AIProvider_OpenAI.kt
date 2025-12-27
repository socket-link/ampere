package link.socket.ampere.domain.ai.provider

import com.aallam.openai.client.OpenAI as Client
import link.socket.ampere.core.config.KotlinConfig
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.tool.AITool_OpenAI

private const val ID = "openai"
private const val NAME = "OpenAI"

data object AIProvider_OpenAI : AIProvider<AITool_OpenAI, AIModel_OpenAI> {

    override val id: ProviderId = ID
    override val name: String = NAME
    override val apiToken: String = KotlinConfig.openai_api_key
    override val availableModels: List<AIModel_OpenAI> = AIModel_OpenAI.ALL_MODELS

    override val client: Client by lazy {
        AIProvider.createClient(
            token = apiToken,
        )
    }
}
