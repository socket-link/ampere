package link.socket.ampere.agents.domain.routing.local

import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.ProseFormat

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
