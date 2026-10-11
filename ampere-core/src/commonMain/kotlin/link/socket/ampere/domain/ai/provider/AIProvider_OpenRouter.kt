@file:Suppress("ktlint:standard:class-naming")

package link.socket.ampere.domain.ai.provider

import com.aallam.openai.client.OpenAI as Client
import link.socket.ampere.core.config.KotlinConfig
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.tool.AITool

private const val ID = "openrouter"
private const val NAME = "OpenRouter"
private const val OPENROUTER_API_ENDPOINT = "https://openrouter.ai/api/v1/"

/**
 * A route, not a maker (AMPR-434): one key that reaches models from several makers
 * through an OpenAI-compatible endpoint. A model reached this way is the same
 * [AIModel] object it is through its maker's key — [AIModel.makerId] still names
 * the maker — and only the id sent on the wire differs; see [OpenRouterModelSlugs].
 *
 * Deliberately absent from [AIProvider.ALL_PROVIDERS], which lists the makers and
 * from which the model catalogue is built. Registering these models a second time
 * would give the catalogue two rows for one model.
 */
data object AIProvider_OpenRouter : AIProvider<AITool, AIModel> {

    override val id: ProviderId = ID
    override val name: String = NAME
    override val apiToken: String = KotlinConfig.openrouter_api_key

    override val availableModels: List<AIModel> by lazy {
        (AIModel_Claude.ALL_MODELS + AIModel_OpenAI.ALL_MODELS + AIModel_Gemini.ALL_MODELS)
            .filter { model -> OpenRouterModelSlugs.slugFor(model) != null }
    }

    override val client: Client by lazy {
        AIProvider.createClient(
            token = apiToken,
            url = OPENROUTER_API_ENDPOINT,
        )
    }

    internal fun withApiToken(apiToken: String): AIProvider<AITool, AIModel> =
        RuntimeAIProvider(
            id = id,
            name = name,
            apiToken = apiToken,
            availableModels = availableModels,
            baseUrl = OPENROUTER_API_ENDPOINT,
        )
}
