package link.socket.ampere.agents.domain.reasoning

import co.touchlab.kermit.Logger
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.domain.util.toClientModelId
import link.socket.ampere.util.ioDispatcher
import link.socket.ampere.util.logWith

/**
 * Centralized service for LLM interactions used by autonomous agents.
 *
 * This service provides:
 * - Consistent LLM calling patterns across all agent types
 * - Support for custom LLM providers for testing and integration
 * - Automatic response cleaning and JSON parsing
 * - Token usage logging for monitoring
 * - Configurable temperature and token limits
 *
 * ## Custom Provider Support
 *
 * When a custom [LlmProvider] is configured, all LLM calls are routed
 * through it instead of the built-in OpenAI client. The system message
 * and user prompt are combined into a single string:
 *
 * ```
 * System: <system message>
 *
 * User: <user prompt>
 * ```
 *
 * This enables:
 * - Testing with mock responses
 * - Integration with custom LLM backends
 * - Prompt interception and transformation
 *
 * Usage:
 * ```kotlin
 * val llmService = AgentLLMService(agentConfiguration)
 * val response = llmService.callForJson(prompt, systemMessage = "You are a planner...")
 * val jsonObj = response.asObject()
 * ```
 *
 * @property agentConfiguration The agent's configuration containing AI provider, model, and optional custom provider
 */
class AgentLLMService(
    private val agentConfiguration: AgentConfiguration,
) {

    private val logger: Logger = logWith("AgentLLMService")

    /**
     * Calls the LLM with a prompt and returns the raw response text.
     *
     * If a custom [LlmProvider] is configured, the call is routed through it.
     * If a [CognitiveRelay][link.socket.ampere.agents.domain.routing.CognitiveRelay]
     * is configured and a [routingContext] is provided, the relay resolves the
     * appropriate [AIConfiguration][link.socket.ampere.domain.ai.configuration.AIConfiguration]
     * before making the call.
     * Otherwise, the built-in OpenAI client is used with the agent's default configuration.
     *
     * @param prompt The user prompt to send
     * @param systemMessage Optional system message to set context
     * @param temperature Response randomness (0.0 = deterministic, 1.0 = creative)
     * @param maxTokens Maximum tokens in response
     * @param routingContext Optional routing context for CognitiveRelay-based model selection
     * @return The raw LLM response text
     */
    suspend fun call(
        prompt: String,
        systemMessage: String = DEFAULT_SYSTEM_MESSAGE,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        routingContext: RoutingContext? = null,
    ): String {
        // Route through custom provider if configured (takes precedence over relay)
        agentConfiguration.llmProvider?.let { provider ->
            logger.d { "[LLM] Using custom provider" }
            val combinedPrompt = buildCombinedPrompt(systemMessage, prompt)
            return withContext(ioDispatcher) {
                provider(combinedPrompt)
            }
        }

        // Resolve configuration through CognitiveRelay if available
        val effectiveConfig = if (routingContext != null) {
            agentConfiguration.cognitiveRelay?.resolve(
                context = routingContext,
                fallbackConfiguration = agentConfiguration.aiConfiguration,
            ) ?: agentConfiguration.aiConfiguration
        } else {
            agentConfiguration.aiConfiguration
        }

        val client = effectiveConfig.provider.client
        val model = effectiveConfig.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = systemMessage,
            ),
            ChatMessage(
                role = ChatRole.User,
                content = prompt,
            ),
        )

        val request = ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        val completion = withContext(ioDispatcher) {
            client.chatCompletion(request)
        }

        // Log token usage for monitoring
        completion.usage?.let { usage ->
            logger.d {
                "[LLM] Tokens - Prompt: ${usage.promptTokens}, " +
                    "Completion: ${usage.completionTokens}, " +
                    "Total: ${usage.totalTokens} (limit: $maxTokens)"
            }
        }

        return completion.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from LLM")
    }

    /**
     * Calls the LLM and parses the response as JSON, returning a structured result.
     *
     * The response is automatically cleaned (markdown code blocks removed) before parsing.
     *
     * @param prompt The user prompt to send
     * @param systemMessage Optional system message (defaults to JSON-focused message)
     * @param temperature Response randomness
     * @param maxTokens Maximum tokens in response
     * @param routingContext Optional routing context for CognitiveRelay-based model selection
     * @return LLMJsonResponse wrapping the parsed JSON
     */
    suspend fun callForJson(
        prompt: String,
        systemMessage: String = JSON_SYSTEM_MESSAGE,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        routingContext: RoutingContext? = null,
    ): LLMJsonResponse {
        val rawResponse = call(
            prompt = prompt,
            systemMessage = systemMessage,
            temperature = temperature,
            maxTokens = maxTokens,
            routingContext = routingContext,
        )

        val cleanedResponse = LLMResponseParser.cleanJsonResponse(rawResponse)
        return LLMJsonResponse(cleanedResponse)
    }

    /**
     * Calls the LLM expecting a JSON object response.
     *
     * @param prompt The user prompt to send
     * @param systemMessage Optional system message
     * @param temperature Response randomness
     * @param maxTokens Maximum tokens in response
     * @param routingContext Optional routing context for CognitiveRelay-based model selection
     * @return The parsed JsonObject
     * @throws IllegalStateException if response is not a valid JSON object
     */
    suspend fun callForJsonObject(
        prompt: String,
        systemMessage: String = JSON_SYSTEM_MESSAGE,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        routingContext: RoutingContext? = null,
    ): JsonObject {
        return callForJson(prompt, systemMessage, temperature, maxTokens, routingContext).asObject()
    }

    /**
     * Calls the LLM expecting a JSON array response.
     *
     * @param prompt The user prompt to send
     * @param systemMessage Optional system message
     * @param temperature Response randomness
     * @param maxTokens Maximum tokens in response
     * @param routingContext Optional routing context for CognitiveRelay-based model selection
     * @return The parsed JsonArray
     * @throws IllegalStateException if response is not a valid JSON array
     */
    suspend fun callForJsonArray(
        prompt: String,
        systemMessage: String = JSON_SYSTEM_MESSAGE,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        routingContext: RoutingContext? = null,
    ): JsonArray {
        return callForJson(prompt, systemMessage, temperature, maxTokens, routingContext).asArray()
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 0.3
        const val DEFAULT_MAX_TOKENS = 4000

        const val DEFAULT_SYSTEM_MESSAGE =
            "You are an autonomous agent component. Respond clearly and concisely."

        const val JSON_SYSTEM_MESSAGE =
            "You are an autonomous agent component. Respond only with valid JSON, no explanations or markdown."

        /**
         * Builds a combined prompt from system message and user prompt.
         *
         * Format:
         * ```
         * System: <system message>
         *
         * User: <user prompt>
         * ```
         *
         * @param systemMessage The system context message
         * @param prompt The user's prompt
         * @return Combined prompt string
         */
        fun buildCombinedPrompt(systemMessage: String, prompt: String): String {
            return """
                |System: $systemMessage
                |
                |User: $prompt
            """.trimMargin()
        }
    }
}

/**
 * Wrapper for a parsed LLM JSON response with convenient accessors.
 *
 * @property rawJson The cleaned JSON string
 */
class LLMJsonResponse(val rawJson: String) {

    /**
     * Parses the response as a JsonObject.
     *
     * @throws IllegalStateException if response is not a valid JSON object
     */
    fun asObject(): JsonObject = LLMResponseParser.parseJsonObject(rawJson)

    /**
     * Parses the response as a JsonArray.
     *
     * @throws IllegalStateException if response is not a valid JSON array
     */
    fun asArray(): JsonArray = LLMResponseParser.parseJsonArray(rawJson)

    /**
     * Attempts to parse as JsonObject, returning null on failure.
     */
    fun asObjectOrNull(): JsonObject? = runCatching { asObject() }.getOrNull()

    /**
     * Attempts to parse as JsonArray, returning null on failure.
     */
    fun asArrayOrNull(): JsonArray? = runCatching { asArray() }.getOrNull()
}
