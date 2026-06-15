package link.socket.ampere.agents.domain.reasoning

import co.touchlab.kermit.Logger
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingFloorUnmetException
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.ai.pricing.ProviderPricingCalculator
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.domain.util.toClientModelId
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient
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
    private val eventApi: AgentEventApi? = null,
    /**
     * Optional prompt prefix evaluated at call time and prepended to [systemMessage].
     *
     * Used by spark-based agents to inject the current spark stack's prompt into
     * the LLM payload at the moment of the call (not at construction). Returning
     * `null` from the provider — or omitting it entirely — leaves [systemMessage]
     * unchanged.
     */
    private val activePromptProvider: (() -> String?)? = null,
    /**
     * Injection seam for outbound LLM calls. Defaults to whatever the
     * [agentConfiguration] carries (which itself defaults to
     * [BundledUpstreamLlmClient] — the pre-seam direct-call path).
     * Embedded consumers (e.g. Socket) typically set the client on
     * [AgentConfiguration.upstreamLlmClient] so it flows through
     * [link.socket.ampere.agents.domain.reasoning.AgentReasoning];
     * passing this argument explicitly overrides whatever the config says,
     * for direct-construction tests that don't want to round-trip through
     * the config.
     *
     * Local, on-device execution flows through this same seam: a
     * [link.socket.ampere.llm.DispatchingUpstreamLlmClient] supplied here (or on
     * the config) routes a relay-selected local configuration to a
     * [link.socket.ampere.llm.LocalUpstreamLlmClient], or to the bundled cloud
     * path otherwise — so [call] is unchanged whether the resolved provider is
     * local or cloud.
     *
     * Note: a custom [link.socket.ampere.domain.llm.LlmProvider] configured
     * on [AgentConfiguration] short-circuits before this client runs.
     */
    private val upstreamLlmClient: UpstreamLlmClient = agentConfiguration.upstreamLlmClient,
) {

    private val logger: Logger = logWith("AgentLLMService")

    val agentId: String?
        get() = eventApi?.agentId

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
        val effectiveSystemMessage = applyActivePromptProvider(systemMessage)

        // Route through custom provider if configured (takes precedence over relay)
        agentConfiguration.llmProvider?.let { provider ->
            logger.d { "[LLM] Using custom provider" }
            val combinedPrompt = buildCombinedPrompt(effectiveSystemMessage, prompt)
            val providerId = resolveCustomProviderId()
            val modelId = resolveCustomModelId()
            emitStartedTelemetry(
                routingContext = routingContext,
                providerId = providerId,
                modelId = modelId,
                routingReason = "custom_provider",
            )

            val startedAt = Clock.System.now()
            return try {
                val response = withContext(ioDispatcher) {
                    provider(combinedPrompt)
                }
                emitCompletedTelemetry(
                    routingContext = routingContext,
                    providerId = providerId,
                    modelId = modelId,
                    usage = TokenUsage(),
                    success = true,
                    startedAt = startedAt,
                )
                response
            } catch (t: Throwable) {
                emitCompletedTelemetry(
                    routingContext = routingContext,
                    providerId = providerId,
                    modelId = modelId,
                    usage = TokenUsage(),
                    success = false,
                    startedAt = startedAt,
                    errorType = t::class.simpleName ?: "UnknownError",
                )
                throw t
            }
        }

        // Resolve configuration through CognitiveRelay if available
        val routingResolution = if (routingContext != null) {
            agentConfiguration.cognitiveRelay?.resolveWithMetadata(
                context = routingContext,
                fallbackConfiguration = agentConfiguration.aiConfiguration,
            ) ?: RoutingResolution.Success(
                configuration = agentConfiguration.aiConfiguration,
                reason = "agent_configuration",
            )
        } else {
            RoutingResolution.Success(
                configuration = agentConfiguration.aiConfiguration,
                reason = "agent_configuration",
            )
        }
        val effectiveConfig = when (routingResolution) {
            is RoutingResolution.Success -> routingResolution.configuration
            is RoutingResolution.FloorUnmet -> throw RoutingFloorUnmetException(
                requestedFloor = routingResolution.requestedFloor,
                bestAvailableRung = routingResolution.bestAvailableRung,
            )
        }

        val model = effectiveConfig.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = effectiveSystemMessage,
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

        emitStartedTelemetry(
            routingContext = routingContext,
            providerId = effectiveConfig.provider.id,
            modelId = model.name,
            routingReason = routingResolution.reason,
        )

        val startedAt = Clock.System.now()
        val completion = try {
            withContext(ioDispatcher) {
                upstreamLlmClient.call(request, effectiveConfig)
            }
        } catch (t: Throwable) {
            emitCompletedTelemetry(
                routingContext = routingContext,
                providerId = effectiveConfig.provider.id,
                modelId = model.name,
                usage = TokenUsage(),
                success = false,
                startedAt = startedAt,
                errorType = t::class.simpleName ?: "UnknownError",
            )
            throw t
        }

        // Log token usage for monitoring
        completion.usage?.let { usage ->
            logger.d {
                "[LLM] Tokens - Prompt: ${usage.promptTokens}, " +
                    "Completion: ${usage.completionTokens}, " +
                    "Total: ${usage.totalTokens} (limit: $maxTokens)"
            }
        }

        val usage = enrichUsageWithEstimatedCost(
            providerId = effectiveConfig.provider.id,
            modelId = model.name,
            usage = TokenUsageExtractor.fromOpenAiUsage(completion.usage),
        )

        emitCompletedTelemetry(
            routingContext = routingContext,
            providerId = effectiveConfig.provider.id,
            modelId = model.name,
            usage = usage,
            success = true,
            startedAt = startedAt,
        )

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

    private suspend fun emitStartedTelemetry(
        routingContext: RoutingContext?,
        providerId: String,
        modelId: String,
        routingReason: String,
    ) {
        val publishingAgentId = eventApi?.agentId ?: return
        eventApi.publish(
            ProviderCallStartedEvent(
                eventId = generateUUID("llm-start", publishingAgentId),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(publishingAgentId),
                workflowId = routingContext?.workflowId,
                agentId = routingContext?.agentId ?: publishingAgentId,
                cognitivePhase = routingContext?.phase,
                providerId = providerId,
                modelId = modelId,
                routingReason = routingReason,
            ),
        )
    }

    private suspend fun emitCompletedTelemetry(
        routingContext: RoutingContext?,
        providerId: String,
        modelId: String,
        usage: TokenUsage,
        success: Boolean,
        startedAt: kotlinx.datetime.Instant,
        errorType: String? = null,
    ) {
        val publishingAgentId = eventApi?.agentId ?: return
        val completedAt = Clock.System.now()
        eventApi.publish(
            ProviderCallCompletedEvent(
                eventId = generateUUID("llm-complete", publishingAgentId),
                timestamp = completedAt,
                eventSource = EventSource.Agent(publishingAgentId),
                workflowId = routingContext?.workflowId,
                agentId = routingContext?.agentId ?: publishingAgentId,
                cognitivePhase = routingContext?.phase,
                providerId = providerId,
                modelId = modelId,
                usage = usage,
                latencyMs = (completedAt - startedAt).inWholeMilliseconds,
                success = success,
                errorType = errorType,
            ),
        )
    }

    private fun applyActivePromptProvider(systemMessage: String): String {
        val provider = activePromptProvider ?: return systemMessage
        val activePrompt = provider.invoke()?.takeIf { it.isNotBlank() } ?: return systemMessage
        return if (systemMessage.isBlank()) activePrompt else "$activePrompt\n\n$systemMessage"
    }

    private fun resolveCustomModelId(): String =
        runCatching { agentConfiguration.aiConfiguration.model.name }
            .getOrDefault("custom")

    private fun resolveCustomProviderId(): String =
        runCatching { agentConfiguration.aiConfiguration.provider.id }
            .getOrDefault("custom")

    internal suspend fun enrichUsageWithEstimatedCost(
        providerId: String,
        modelId: String,
        usage: TokenUsage,
        estimateUsd: suspend (providerId: String, modelId: String, inputTokens: Int?, outputTokens: Int?) -> Double? =
            { estimateProviderId, estimateModelId, inputTokens, outputTokens ->
                ProviderPricingCalculator.estimateUsd(
                    providerId = estimateProviderId,
                    modelId = estimateModelId,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                )
            },
    ): TokenUsage {
        val bundledEstimatedCost = runCatching {
            estimateUsd(
                providerId,
                modelId,
                usage.inputTokens,
                usage.outputTokens,
            )
        }.getOrElse { error ->
            logger.w(error) {
                "[LLM] Failed to resolve bundled pricing for $providerId/$modelId"
            }
            null
        }

        return if (bundledEstimatedCost != null) {
            usage.copy(estimatedCost = bundledEstimatedCost)
        } else {
            usage
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
