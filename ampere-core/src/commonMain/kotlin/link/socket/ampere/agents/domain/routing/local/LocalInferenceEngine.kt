package link.socket.ampere.agents.domain.routing.local

import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.ProseFormat

/**
 * On-device inference contract for local, 0-Watt generation.
 *
 * This is the platform seam for AMPR-203's local execution surface. It is
 * intentionally **SDK-free** (no `com.aallam.openai` types) and text-shaped —
 * text in, text out — which is the Android floor for v1. Apple's guided
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
 * [UpstreamLlmClient][link.socket.ampere.llm.UpstreamLlmClient] seam, turning
 * a `ChatCompletionRequest` into a [LocalGenerationRequest] — the system
 * message, the user's turn, the output budget and the temperature kept apart —
 * and wrapping the returned text back into a `ChatCompletion`. Keeping the SDK
 * types out of this interface is what lets platform modules implement it
 * without depending on the OpenAI client.
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
     * The one generation method every engine implements. The client calls the
     * request form below, whose default hands this method the request
     * flattened to one string.
     *
     * Returns a [Result] rather than throwing so the caller can decide how to
     * surface failure. In v1 there is **no silent fallback**: a
     * [Result.failure] is surfaced as an error by
     * [link.socket.ampere.llm.LocalUpstreamLlmClient] (it does not retry on the
     * cloud grid). Execution-failure → grid retry is a deliberate future
     * decision kept out of this seam.
     */
    suspend fun generate(prompt: String): Result<String>

    /**
     * Generate a completion for [request], whose parts arrive apart (AMPR-462):
     * the system message as [LocalGenerationRequest.instructions], the user's
     * turn as [LocalGenerationRequest.prompt], and the output budget and
     * temperature the call was made with.
     *
     * [link.socket.ampere.llm.LocalUpstreamLlmClient] calls this form. An engine
     * whose framework takes a system message and an output budget separately
     * overrides it and passes them on, so the budget a caller set bounds what
     * the model writes rather than being only the figure the relay reserved
     * when it decided the prompt fits.
     *
     * The default hands [generate] the request's
     * [flattenedPrompt][LocalGenerationRequest.flattenedPrompt], which is byte
     * for byte the prompt the client sent before this method existed. An
     * engine written against [generate] alone therefore compiles unchanged,
     * keeps receiving that prompt, and never sees the budget or the
     * temperature. An engine that wraps another must forward this method as
     * well as [generate], or every request reaches the inner engine flattened.
     *
     * The failure contract is [generate]'s: a [Result.failure] is surfaced and
     * never retried on the cloud grid. A reply the engine cut short at
     * [LocalGenerationRequest.maxOutputTokens] is a reply, not a failure.
     */
    suspend fun generate(request: LocalGenerationRequest): Result<String> =
        generate(request.flattenedPrompt)

    /**
     * How many tokens the engine's own tokenizer makes of [text], or `null` when
     * the engine cannot say (AMPR-460).
     *
     * The relay decides whether a prompt fits a model's context window from a
     * token figure, and without one from the engine it falls back to
     * `PromptTokenEstimator`'s four characters per token. That estimate runs
     * high on ordinary prose and low on digit-dense text — a log with
     * timestamps measured 1.55× the estimate against Apple's tokenizer
     * (AMPR-372) — and a prompt admitted on an undercount is one the model then
     * refuses mid-call. An engine that can count is asked to count the
     * request's [flattenedPrompt][LocalGenerationRequest.flattenedPrompt]:
     * exactly what an engine implementing only the prompt form of [generate] is
     * handed, and the same text, less its two role labels, that an engine
     * taking the request apart passes on.
     *
     * Only ever *sizes* a call: a count never makes work eligible for the
     * device, which is the routing floor's decision. Stateless, like
     * [generate]: no session is kept to count on. The default is `null`, so an
     * engine written before this seam compiles unchanged and is sized by the
     * estimate as before. An engine that throws while counting is treated as
     * one that cannot count; the call proceeds on the estimate.
     */
    suspend fun tokenCount(text: String): Int? = null

    /**
     * Generate a structured [EmissionPayload] shaped like [kind] directly —
     * the point of guided/constrained on-device generation (AMPR-225; e.g.
     * Apple's `@Generable`): the returned payload is produced under a
     * generation-time shape constraint, not parsed out of free text after the
     * fact — no parse-and-pray.
     *
     * Additive to [generate]: an engine that only implements the text-shaped
     * path (e.g. a first-cut Gemini Nano binding) need not override this at
     * all. The default here can only honor [EmissionKind.Prose] — the one
     * payload a raw string already represents without guessing structure —
     * and fails for every other [kind], so callers never silently receive an
     * ill-shaped payload from an engine that hasn't implemented real guided
     * generation.
     */
    suspend fun generateStructured(kind: EmissionKind, prompt: String): Result<EmissionPayload> =
        when (kind) {
            EmissionKind.Prose ->
                generate(prompt).map { text -> EmissionPayload.Prose(text = text, format = ProseFormat.PLAIN) }
            else ->
                Result.failure(
                    UnsupportedOperationException(
                        "This engine does not support structured generation for $kind",
                    ),
                )
        }
}

/**
 * One on-device generation, with the parts a chat request keeps apart still
 * apart (AMPR-462).
 *
 * [link.socket.ampere.llm.LocalUpstreamLlmClient] builds one from each request
 * it is handed and calls [LocalInferenceEngine.generate] with it. An engine
 * that can put a system message and an output budget where its framework
 * expects them reads the fields — Apple's passes them as a session's
 * instructions and as its generation options. An engine that takes a single
 * prompt reads [flattenedPrompt], which is what the interface's default does.
 *
 * @property instructions The system message, or null when the request carried
 *   none.
 * @property prompt The user's turn.
 * @property maxOutputTokens The most tokens the reply may run to, or null when
 *   the request set no cap. `AgentLLMService` sets one on every call: the
 *   budget the relay reserved out of the model's window when it decided the
 *   prompt fits.
 * @property temperature The sampling temperature, or null when the request
 *   carried none. The service omits it for a model that rejects sampling
 *   parameters. An engine maps it onto its own framework's range.
 */
data class LocalGenerationRequest(
    val instructions: String?,
    val prompt: String,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
) {
    /**
     * The request as the one string a prompt-only engine is handed:
     * `System: <instructions>`, a blank line and `User: <prompt>`, or
     * `User: <prompt>` alone when there are no instructions. The budget and
     * the temperature have no place in it.
     *
     * The layout `LocalUpstreamLlmClient` sent before engines received a
     * request, byte for byte, and the one place it is written. The relay's
     * window fit counts this string (AMPR-460).
     */
    val flattenedPrompt: String
        get() = listOfNotNull(
            instructions?.let { "System: $it" },
            "User: $prompt",
        ).joinToString(separator = "\n\n")
}
