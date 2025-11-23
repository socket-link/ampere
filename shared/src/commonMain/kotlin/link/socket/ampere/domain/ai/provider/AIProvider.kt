@file:Suppress("ClassName")

package link.socket.ampere.domain.ai.provider

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI as Client
import com.aallam.openai.client.OpenAIHost as ClientHost
import kotlin.time.Duration.Companion.hours
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.tool.AITool

typealias ProviderId = String

sealed interface AIProvider<
    TD : AITool,
    L : AIModel,
    > {

    val id: ProviderId
    val name: String
    val availableModels: List<L>
    val apiToken: String

    val client: Client

    companion object Companion {

        val ALL_PROVIDERS = listOf(
            AIProvider_Anthropic,
            AIProvider_Google,
            AIProvider_OpenAI,
        )

        fun createClient(
            token: String,
            url: String? = null,
        ): Client = Client(
            token = token,
            logging = LoggingConfig(
                logLevel = LogLevel.All,
                logger = Logger.Simple,
            ),
            timeout = Timeout(socket = 1.hours),
            host = url?.let { hostUrl ->
                ClientHost(
                    baseUrl = hostUrl,
                )
            } ?: ClientHost.OpenAI,
        )
    }
}
