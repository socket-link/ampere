package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * [UpstreamLlmClient] that executes a relay-selected **local** configuration
 * through an on-device [LocalInferenceEngine].
 *
 * This is the local counterpart to [BundledUpstreamLlmClient]: where the bundled
 * client performs an HTTP/OpenAI round-trip via `configuration.provider.client`,
 * this client adapts the same [ChatCompletionRequest] onto the text-shaped local
 * engine. It is the execution half of AMPR-203's local surface — local routes
 * through the observable [UpstreamLlmClient] seam (D1), never the
 * [LlmProvider][link.socket.ampere.domain.llm.LlmProvider] bypass.
 *
 * ## Adaptation
 *
 * - **Request → engine request:** the system message and the user message
 *   become a [LocalGenerationRequest]'s instructions and prompt, with the
 *   request's `max_tokens` as its output budget and its temperature carried
 *   over (AMPR-462), and the engine is called with that. An engine that
 *   implements only the prompt form receives it flattened to one
 *   `Role: content` block per message, as before. Any other shape of request —
 *   a conversation, say — has no faithful split and goes to the prompt form
 *   flattened (see [flattenToPrompt]). v1 is text-only; structured output and
 *   tool-calling over the local seam are out of scope.
 * - **Text → response:** the engine's text is wrapped in a minimal
 *   [ChatCompletion] matching [BundledUpstreamLlmClient]'s return shape — one
 *   assistant choice carrying the text. No `usage` is attached: a local call has
 *   no token-billing meaning, and its 0-Watt accounting is anchored by the
 *   provider's `CostPolicy.Free` descriptor (T5), not by token counts.
 *
 * ## What served it
 *
 * [callDetailed] reports a [ServedBy] (AMPR-391): the engine on this device
 * served the call, so the record says so rather than leaving Ampere to assume
 * it from the resolved configuration — an on-device call is labelled by what
 * ran it. It names the on-device provider and model the dispatch keyed on and
 * reports `usage = null`, which is a positive statement that a local generation
 * has no token accounting rather than an absence of information.
 *
 * It deliberately does **not** substitute an engine-private model identity for
 * the catalog's. Two readers key on the pair of ids: the on-device ledger in
 * [OnDeviceInferenceProjection][link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceProjection]
 * balances a start against a completion on `(workflowId, agentId, providerId,
 * modelId, cognitivePhase)`, and
 * [DispatchingUpstreamLlmClient.localityOf] resolves a descriptor by model id.
 * A name the catalog does not hold would leave the "generating now" indicator
 * stuck on and label a call that never left the device as `CLOUD`. The engine's
 * own reported model reaches a surface through
 * [LocalCapacity.modelId][link.socket.ampere.agents.domain.routing.local.LocalCapacity.modelId]
 * instead, where it already does.
 *
 * ## Errors
 *
 * On [Result.failure] from the engine, this client throws a
 * [LocalInferenceException] rather than silently falling back to the cloud grid
 * — v1 surfaces local failure (no silent fallback). The exception propagates to
 * [link.socket.ampere.agents.domain.reasoning.AgentLLMService], which emits
 * failure telemetry from its catch site, exactly as the [UpstreamLlmClient]
 * contract specifies for transport errors.
 */
@AmpereStableApi
class LocalUpstreamLlmClient(
    private val engine: LocalInferenceEngine,
) : UpstreamLlmClient {

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion = callDetailed(request, configuration).completion

    override suspend fun callDetailed(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): UpstreamCompletion {
        val generation = request.toLocalGenerationRequest()
        val result = if (generation != null) {
            engine.generate(generation)
        } else {
            engine.generate(flattenToPrompt(request.messages))
        }
        val text = result.getOrElse { cause ->
            throw LocalInferenceException(
                "Local inference engine failed to generate a completion for " +
                    "provider '${configuration.provider.id}'",
                cause,
            )
        }

        return UpstreamCompletion(
            completion = ChatCompletion(
                id = LOCAL_COMPLETION_ID,
                created = 0L,
                model = request.model,
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(
                            role = ChatRole.Assistant,
                            content = text,
                        ),
                    ),
                ),
            ),
            served = ServedBy(
                providerId = configuration.provider.id,
                modelId = configuration.model.name,
                // Nothing else to add. A local generation has no token accounting;
                // its 0-Watt cost is the Free descriptor's, not a second figure here;
                // the relay's reason already says why the device was chosen, and
                // "the engine ran it" in its place would drop which rule selected it;
                // and latency is Ampere's own measurement to take, since the engine
                // runs in this process. Reporting a narrower local figure beside
                // wall-clock cloud ones would bias the comparison a surface draws
                // between them.
                usage = null,
                estimatedCostUsd = null,
                routingReason = null,
                latencyMs = null,
            ),
        )
    }

    companion object {
        /** Synthetic completion id for locally generated responses. */
        const val LOCAL_COMPLETION_ID: String = "local-inference"

        /**
         * The string a prompt-only engine is handed for [messages]. Each message
         * is a `Role: content` block, separated by blank lines — mirroring the
         * system/user layout that
         * [link.socket.ampere.agents.domain.reasoning.AgentLLMService] uses for
         * the [LlmProvider][link.socket.ampere.domain.llm.LlmProvider] bypass, so
         * a local engine sees a familiar shape.
         *
         * The shape an engine request has slots for — a system message and a
         * user message, or a user message alone — is written by
         * [LocalGenerationRequest.flattenedPrompt], which is what the engine
         * contract's default hands on. It produces the same bytes the
         * role-by-role join below gives those messages.
         */
        internal fun flattenToPrompt(messages: List<ChatMessage>): String =
            messages.toInstructionsAndPrompt()?.flattenedPrompt
                ?: messages.joinToString(separator = "\n\n") { message ->
                    val label = message.role.role.replaceFirstChar { it.uppercaseChar() }
                    "$label: ${message.content.orEmpty()}"
                }

        /**
         * The request [callDetailed] hands the engine for [messages], without
         * the budget and temperature no count reads — so a caller sizing the
         * call (AMPR-460, AMPR-462) counts what the engine is given, by the split
         * the client itself makes. Null for messages that split into no request,
         * which the engine is handed as [flattenToPrompt] instead.
         */
        internal fun generationRequestFor(messages: List<ChatMessage>): LocalGenerationRequest? =
            messages.toInstructionsAndPrompt()

        /** [generationRequestFor] for the system-then-user pair every `AgentLLMService` call sends. */
        internal fun generationRequestFor(systemMessage: String, prompt: String): LocalGenerationRequest =
            LocalGenerationRequest(instructions = systemMessage, prompt = prompt)

        /**
         * The engine request for this chat request (AMPR-462), or null when its
         * messages are not the shape the request has slots for.
         *
         * The output budget is the request's `max_tokens`, which
         * `AgentLLMService` sets on every call; a request that sets
         * `max_completion_tokens` instead is honoured too, and the tighter of
         * the two wins when both are set. The temperature is passed only where
         * the request carried one.
         */
        private fun ChatCompletionRequest.toLocalGenerationRequest(): LocalGenerationRequest? =
            messages.toInstructionsAndPrompt()?.copy(
                maxOutputTokens = outputBudget(),
                temperature = temperature,
            )

        /**
         * These messages as instructions and a prompt: a system message followed
         * by a user message, or a user message alone. Anything else — a
         * conversation, a second system message, another role — is null, since
         * no split of it is faithful. A message with no text keeps its slot as an
         * empty string, so the flattened form keeps the bytes the role-by-role
         * join gives it.
         */
        private fun List<ChatMessage>.toInstructionsAndPrompt(): LocalGenerationRequest? {
            val user = lastOrNull()?.takeIf { it.role == ChatRole.User } ?: return null
            val system = when (size) {
                1 -> null
                2 -> first().takeIf { it.role == ChatRole.System } ?: return null
                else -> return null
            }
            return LocalGenerationRequest(
                instructions = system?.let { it.content.orEmpty() },
                prompt = user.content.orEmpty(),
            )
        }

        // `max_tokens` is what AgentLLMService sets; the 4.x client deprecates it for `max_completion_tokens`.
        @Suppress("DEPRECATION")
        private fun ChatCompletionRequest.outputBudget(): Int? =
            listOfNotNull(maxTokens, maxCompletionTokens).minOrNull()
    }
}

/**
 * Raised by [LocalUpstreamLlmClient] when the on-device engine fails to produce
 * a completion. Carries the engine's failure as its [cause] so
 * [link.socket.ampere.agents.domain.reasoning.AgentLLMService] can record a
 * meaningful `errorType` in its completion telemetry.
 */
class LocalInferenceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
