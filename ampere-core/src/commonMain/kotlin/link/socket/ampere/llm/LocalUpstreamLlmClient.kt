package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
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
 * - **Request → prompt:** the request's messages are flattened to a single
 *   `Role: content` block per message (see [flattenToPrompt]). v1 is text-only;
 *   structured output and tool-calling over the local seam are out of scope.
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
        val text = engine.generate(request.flattenToPrompt()).getOrElse { cause ->
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
         * Flatten a [ChatCompletionRequest] to a single prompt string for the
         * text-shaped local engine. Each message becomes a `Role: content` line,
         * separated by blank lines — mirroring the system/user layout that
         * [link.socket.ampere.agents.domain.reasoning.AgentLLMService] uses for
         * the [LlmProvider][link.socket.ampere.domain.llm.LlmProvider] bypass, so
         * a local engine sees a familiar shape.
         */
        internal fun ChatCompletionRequest.flattenToPrompt(): String =
            messages.joinToString(separator = "\n\n") { message ->
                val label = message.role.role.replaceFirstChar { it.uppercaseChar() }
                "$label: ${message.content.orEmpty()}"
            }
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
