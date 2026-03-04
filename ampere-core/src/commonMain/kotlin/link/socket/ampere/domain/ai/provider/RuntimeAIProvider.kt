package link.socket.ampere.domain.ai.provider

import com.aallam.openai.client.OpenAI as Client
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.tool.AITool

internal data class RuntimeAIProvider<
    TD : AITool,
    L : AIModel,
    >(
    override val id: ProviderId,
    override val name: String,
    override val apiToken: String,
    override val availableModels: List<L>,
    private val baseUrl: String? = null,
) : AIProvider<TD, L> {

    override val client: Client by lazy {
        AIProvider.createClient(
            token = apiToken,
            url = baseUrl,
        )
    }
}
