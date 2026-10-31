package link.socket.ampere.relay.local.android

import com.google.mlkit.genai.prompt.Generation
import kotlin.coroutines.cancellation.CancellationException
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * Rung ZERO on Android (AMPR-508): Gemini Nano, reached through ML Kit's Prompt API, bound
 * to ampere-core's [LocalInferenceEngine].
 *
 * A recon skeleton, not the shipping engine. It makes an Android build able to report
 * [LocalCapacity] from the device's model and to generate on it, so the model can be
 * measured on Ampere's real prompts the way AMPR-372 measured Apple's. Which work may reach
 * it is decided by the routing floor, not here; AMPR-372's rule is display text only.
 *
 * ## Egress
 *
 * ML Kit's Prompt API runs on the device or fails; it has no cloud mode to fall back to.
 * A failure — the model busy, the app over its quota, a call from the background, a request
 * too large — comes back as a [Result.failure] carrying ML Kit's own exception, and
 * `CognitiveRelay` decides what happens next. The engine never retries anywhere else.
 *
 * ## The request's parts
 *
 * The system message goes to the model as ML Kit's `SystemInstruction` when the model on
 * this device takes one (Gemini Nano V3 and later), and is folded into the prompt, as
 * [LocalGenerationRequest.flattenedPrompt] lays it out, when it does not. The output cap and
 * the temperature are passed as the request's own. A reply cut short at the cap is a reply.
 *
 * ## Counting
 *
 * ML Kit counts a request with the model's tokenizer (`countTokens`), so the relay sizes a
 * call with the model's count rather than four characters per token. The count is of the
 * request exactly as [generate] would send it, system instruction included, as ML Kit's
 * guide asks.
 */
class MlKitLocalInferenceEngine internal constructor(
    private val model: NanoModel,
) : LocalInferenceEngine, AutoCloseable {

    /** The engine over ML Kit's default Prompt API client. */
    constructor() : this(MlKitNanoModel(Generation.getClient()))

    /**
     * ML Kit's status, as a snapshot the relay's availability gate reads. Asks the model's
     * status and nothing else: it never starts a download, which is a host's decision.
     */
    override suspend fun probe(): LocalCapacity {
        val status = try {
            model.status()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return unavailable(NanoUnavailableReason.STATUS_FAILED)
        }
        return when (status) {
            NanoStatus.AVAILABLE -> LocalCapacity(
                available = true,
                modelId = MODEL_ID,
                maxContextTokens = MAX_CONTEXT_TOKENS,
                providerId = AIProvider_OnDevice.id,
            )
            NanoStatus.DOWNLOADABLE -> unavailable(NanoUnavailableReason.NOT_DOWNLOADED)
            NanoStatus.DOWNLOADING -> unavailable(NanoUnavailableReason.DOWNLOADING)
            NanoStatus.UNAVAILABLE -> unavailable(NanoUnavailableReason.UNAVAILABLE)
        }
    }

    /** The bare form: the prompt alone, ML Kit's own defaults for everything else. */
    override suspend fun generate(prompt: String): Result<String> =
        generate(NanoRequest(systemInstruction = null, prompt = prompt))

    override suspend fun generate(request: LocalGenerationRequest): Result<String> = generate(nanoRequestFor(request))

    override suspend fun tokenCount(text: String): Int? = count(NanoRequest(systemInstruction = null, prompt = text))

    override suspend fun tokenCount(request: LocalGenerationRequest): Int? = count(nanoRequestFor(request))

    /** Releases ML Kit's client. */
    override fun close() = model.close()

    private suspend fun generate(request: NanoRequest): Result<String> =
        try {
            val reply = model.generate(request)
            val text = reply.text
            if (text == null) {
                Result.failure(IllegalStateException("The on-device model returned no candidate"))
            } else {
                Result.success(text)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

    private suspend fun count(request: NanoRequest): Int? =
        try {
            model.countTokens(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null
        }

    /**
     * [request] as the model is handed it. One function for both [generate] and the count,
     * so the count is of what is sent.
     */
    private suspend fun nanoRequestFor(request: LocalGenerationRequest): NanoRequest {
        val instructions = request.instructions
        val separate = instructions != null && systemPromptAvailable()
        return NanoRequest(
            systemInstruction = if (separate) instructions else null,
            prompt = if (instructions == null || separate) request.prompt else request.flattenedPrompt,
            maxOutputTokens = request.maxOutputTokens?.takeIf { it > 0 },
            temperature = request.temperature?.coerceIn(0.0, 1.0)?.toFloat(),
        )
    }

    /** A model that cannot say whether it takes a system instruction is treated as one that does not. */
    private suspend fun systemPromptAvailable(): Boolean =
        try {
            model.systemPromptAvailable()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            false
        }

    private fun unavailable(reason: String): LocalCapacity =
        LocalCapacity(
            available = false,
            providerId = AIProvider_OnDevice.id,
            reason = reason,
        )

    companion object {
        /**
         * The model id the engine reports. No routing descriptor carries it yet: the registry
         * entry that would route to it is Phase 2, and must use this name.
         */
        const val MODEL_ID: String = "gemini-nano-on-device"

        /**
         * The input window ML Kit documents for the Prompt API: "under 4000 tokens". ML Kit
         * also has `getTokenLimit()`, whose figure is not yet measured against this; until it
         * is, the documented figure is the one reported.
         */
        const val MAX_CONTEXT_TOKENS: Int = 4_000
    }
}
