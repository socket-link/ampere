package link.socket.ampere.agents.domain.routing.local

/**
 * On-device inference contract for local, 0-Watt generation.
 *
 * This is the platform seam for AMPR-203's local execution surface. It is
 * intentionally **SDK-free** (no `com.aallam.openai` types) and text-shaped —
 * prompt in, text out — which is the Android floor for v1. Apple's guided
 * generation can be used *inside* its `actual` implementation without widening
 * this contract.
 *
 * ## Binding
 *
 * The local engine is **not** `expect`/`actual`: the real implementations live
 * in sibling platform modules (`:ampere-relay-local-android`,
 * `:ampere-relay-local-apple`), and `expect`/`actual` cannot cross module
 * boundaries. Instead it is a `commonMain` interface bound per platform (the
 * `:phosphor-lumos-{platform}` pattern). `:ampere-core` binds **no** engine;
 * the dispatching seam ([link.socket.ampere.llm.DispatchingUpstreamLlmClient])
 * therefore receives `null` here and routes every call to the bundled cloud
 * path until a platform module supplies a real engine.
 *
 * ## Relationship to the LLM seam
 *
 * [link.socket.ampere.llm.LocalUpstreamLlmClient] adapts this text-shaped
 * contract onto Ampere's
 * [UpstreamLlmClient][link.socket.ampere.llm.UpstreamLlmClient] seam, flattening
 * a `ChatCompletionRequest` to a prompt and wrapping the returned text back into
 * a `ChatCompletion`. Keeping the SDK types out of this interface is what lets
 * platform modules implement it without depending on the OpenAI client.
 */
interface LocalInferenceEngine {

    /**
     * Report whether the engine can serve a generation right now (model loaded,
     * device not thermally throttled, etc.). Cheap and side-effect-free; callers
     * may invoke it per-route to gate a device-gated provider.
     */
    suspend fun probe(): LocalCapacity

    /**
     * Generate a completion for [prompt].
     *
     * Returns a [Result] rather than throwing so the caller can decide how to
     * surface failure. In v1 there is **no silent fallback**: a
     * [Result.failure] is surfaced as an error by
     * [link.socket.ampere.llm.LocalUpstreamLlmClient] (it does not retry on the
     * cloud grid). Execution-failure → grid retry is a deliberate future
     * decision kept out of this seam.
     */
    suspend fun generate(prompt: String): Result<String>
}
