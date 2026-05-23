package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * Public injection seam for outbound LLM calls.
 *
 * `UpstreamLlmClient` is the call-origination boundary between Ampere's
 * reasoning layer and the actual network round-trip to an LLM provider.
 * Embedded consumers (e.g. the Socket client) implement this to route LLM
 * calls through their own backend proxy instead of calling the per-provider
 * OpenAI-compatible client directly.
 *
 * ## Relationship to existing seams
 *
 * Ampere already exposes two LLM-related injection points:
 *
 * - [link.socket.ampere.domain.llm.LlmProvider] (`suspend (String) -> String`) —
 *   prompt-in / text-out. Drops below the message/role boundary and is meant
 *   for testing or simple prompt interception. When set on
 *   [link.socket.ampere.agents.config.AgentConfiguration], it short-circuits
 *   the call before `UpstreamLlmClient` ever runs.
 * - [link.socket.ampere.agents.domain.routing.CognitiveRelay] — selects which
 *   [AIConfiguration] should handle a given routing context. Runs *before*
 *   `UpstreamLlmClient` and is unaffected by it.
 *
 * `UpstreamLlmClient` sits below those: after the relay has picked the
 * config and Ampere has materialized a full
 * [ChatCompletionRequest][com.aallam.openai.api.chat.ChatCompletionRequest],
 * the request is handed to this client to perform the actual call. This is
 * the natural seam for Socket's backend proxy: same request shape, same
 * response shape, just a different network endpoint.
 *
 * ## Default behavior
 *
 * The bundled default ([BundledUpstreamLlmClient]) calls
 * `configuration.provider.client.chatCompletion(request)` — exactly what
 * Ampere did before this seam existed. Existing callers see no change.
 *
 * ## Streaming
 *
 * This seam is intentionally non-streaming. Ampere's reasoning surface today
 * is non-streaming (`AgentLLMService.call` returns full strings), which
 * matches the MVP shape Socket's proxy targets. A streaming variant will be
 * added in a follow-up ticket once both sides require it.
 *
 * ## Errors
 *
 * Implementations should let underlying transport errors propagate;
 * [link.socket.ampere.agents.domain.reasoning.AgentLLMService] emits failure
 * telemetry from the catch site.
 */
@AmpereStableApi
interface UpstreamLlmClient {

    /**
     * Execute a single chat completion request against the upstream LLM.
     *
     * @param request The fully materialized request (model, messages,
     *   temperature, max tokens). Ampere has already applied any
     *   [CognitiveRelay][link.socket.ampere.agents.domain.routing.CognitiveRelay]
     *   routing and active-prompt-provider injection before this call.
     * @param configuration The [AIConfiguration] that produced [request].
     *   Implementations may use it to select a network endpoint, attach
     *   authentication, or annotate logs — Ampere passes it through so the
     *   client can honor relay decisions without re-deriving them.
     * @return The raw [ChatCompletion] response. The first choice's
     *   `message.content` is what Ampere returns to the caller.
     */
    suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion
}

/**
 * Bundled [UpstreamLlmClient] that routes calls through the per-provider
 * `OpenAI` client constructed by
 * [link.socket.ampere.domain.ai.provider.AIProvider].
 *
 * This is Ampere's default; using it is byte-equivalent to the pre-seam
 * direct `provider.client.chatCompletion(request)` call.
 */
@AmpereStableApi
object BundledUpstreamLlmClient : UpstreamLlmClient {
    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion = configuration.provider.client.chatCompletion(request)
}
