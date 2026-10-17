package link.socket.ampere.cli.ondevice

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * Apple's on-device model as a [LocalInferenceEngine] a JVM can hold, reached through
 * `ampere-helper` (AMPR-375).
 *
 * `FoundationModels` is a Swift-only framework with no C or Objective-C headers, so the JVM
 * cannot call it. The helper is a Swift process that can. This engine speaks three of its ops,
 * through [AmpereHelperProcess]: `inference.probe`, `inference.generate` and `inference.count`.
 * The last two take a request's parts apart, as the iOS engine passes them to the framework
 * (AMPR-462).
 *
 * ## What the probe reports
 *
 * What the iOS engine reports, stamped here rather than in Swift: the on-device provider, the
 * catalog's model id, the window the system reported, and the [LocalUnavailableReason] code for
 * whatever the system said. The model id has to be the catalog's: the relay selects the on-device
 * route by that name, and the on-device ledger pairs a call's start with its completion on it.
 *
 * A helper that cannot be used reads as unavailable with a reason, never as a failed call. One
 * that speaks another protocol or serves no inference is
 * [LocalUnavailableReason.HELPER_INCOMPATIBLE]; one that could not start, exited or did not answer
 * is [LocalUnavailableReason.HELPER_UNREACHABLE], and the next probe starts a fresh one.
 *
 * ## It never throws
 *
 * [probe] answers unavailable-with-a-code; [generate] answers [Result.failure] for a refusal, a
 * timeout or an exit, and the failure is the helper's reason; [tokenCount] answers null whenever
 * it cannot count, including on a helper too old to know `inference.count`. Probing spawns the
 * helper the first time, which is the one side effect a probe has here.
 */
internal class HelperLocalInferenceEngine(
    private val helper: AmpereHelperProcess,
    private val timeouts: HelperInferenceTimeouts = HelperInferenceTimeouts(),
) : LocalInferenceEngine {

    override suspend fun probe(): LocalCapacity =
        helper.call(
            op = AmpereHelperWire.Op.INFERENCE_PROBE,
            params = null,
            serializer = InferenceProbeResult.serializer(),
            timeout = timeouts.probe,
        ).fold(
            onSuccess = { probed -> probed.toCapacity() },
            onFailure = { failure -> unavailable(failure.toReasonCode()) },
        )

    /** The bare form, for a caller with one prompt: no instructions, the framework's own options. */
    override suspend fun generate(prompt: String): Result<String> = generate(InferenceGenerateParams(prompt = prompt))

    /**
     * The form `LocalUpstreamLlmClient` calls (AMPR-462). The system message goes as the session's
     * instructions, the user's turn as the prompt, and the output budget and temperature as the
     * framework's generation options, so the budget the caller set bounds what the model writes. A
     * reply the cap cuts short is a reply, not a failure.
     */
    override suspend fun generate(request: LocalGenerationRequest): Result<String> =
        generate(
            InferenceGenerateParams(
                prompt = request.prompt,
                instructions = request.instructions,
                maxOutputTokens = request.maxOutputTokens,
                temperature = request.temperature,
            ),
        )

    private suspend fun generate(params: InferenceGenerateParams): Result<String> =
        helper.call(
            op = AmpereHelperWire.Op.INFERENCE_GENERATE,
            params = AmpereHelperWire.json.encodeToJsonElement(InferenceGenerateParams.serializer(), params),
            serializer = InferenceGenerateResult.serializer(),
            timeout = timeouts.generate,
        ).map { generated -> generated.text }

    /**
     * The model's own count of [text], so the relay sizes a prompt with the tokenizer that will
     * read it (AMPR-460). Null on a system before macOS 26.4, on a helper without
     * `inference.count`, and on any failure: a count only ever sizes a call, and failing to count
     * is never a failed call.
     */
    override suspend fun tokenCount(text: String): Int? = count(InferenceCountParams(text = text))

    /**
     * What [request] costs the model as input, which is what the relay's window fit asks
     * (AMPR-462): the instructions and prompt a session for it starts from, counted by the model,
     * as the iOS engine counts it. A string count misses the system turn the framework wraps
     * around every call. The same nulls as the string form.
     */
    override suspend fun tokenCount(request: LocalGenerationRequest): Int? =
        count(InferenceCountParams(prompt = request.prompt, instructions = request.instructions))

    private suspend fun count(params: InferenceCountParams): Int? =
        helper.call(
            op = AmpereHelperWire.Op.INFERENCE_COUNT,
            params = AmpereHelperWire.json.encodeToJsonElement(InferenceCountParams.serializer(), params),
            serializer = InferenceCountResult.serializer(),
            timeout = timeouts.count,
        ).getOrNull()?.tokens

    private fun InferenceProbeResult.toCapacity(): LocalCapacity =
        when (availability) {
            AmpereHelperWire.Availability.AVAILABLE -> LocalCapacity(
                available = true,
                modelId = MODEL_ID,
                maxContextTokens = contextSize,
                providerId = AIProvider_OnDevice.id,
            )
            AmpereHelperWire.Availability.DEVICE_NOT_ELIGIBLE ->
                unavailable(LocalUnavailableReason.DEVICE_NOT_ELIGIBLE)
            AmpereHelperWire.Availability.APPLE_INTELLIGENCE_NOT_ENABLED ->
                unavailable(LocalUnavailableReason.NOT_ENABLED)
            AmpereHelperWire.Availability.MODEL_NOT_READY ->
                unavailable(LocalUnavailableReason.MODEL_NOT_READY)
            AmpereHelperWire.Availability.OS_TOO_OLD ->
                unavailable(LocalUnavailableReason.OS_TOO_OLD)
            else -> unavailable(LocalUnavailableReason.UNAVAILABLE)
        }

    private fun unavailable(reason: String): LocalCapacity =
        LocalCapacity(available = false, providerId = AIProvider_OnDevice.id, reason = reason)

    private fun Throwable.toReasonCode(): String =
        when (this) {
            is AmpereHelperException.Incompatible -> LocalUnavailableReason.HELPER_INCOMPATIBLE
            is AmpereHelperException.Refused ->
                when (code) {
                    AmpereHelperWire.ErrorCode.UNKNOWN_OP,
                    AmpereHelperWire.ErrorCode.UNSUPPORTED_PROTOCOL,
                    -> LocalUnavailableReason.HELPER_INCOMPATIBLE
                    else -> LocalUnavailableReason.HELPER_UNREACHABLE
                }
            else -> LocalUnavailableReason.HELPER_UNREACHABLE
        }

    private companion object {
        /** The catalog's name for the model, as the iOS engine reports it. */
        val MODEL_ID: String = AIModel_OnDevice.AppleFoundationModels.name
    }
}

/**
 * How long each op gets.
 *
 * A generation is cut at 60 seconds, the same cut Ampere puts on a phase call. On Apple silicon a
 * short answer takes under a second and a near-window prompt about three (AMPR-373), so a call
 * still running at a minute has stalled: AMPR-372 saw 24 of 516 background generations stall for
 * between 60 seconds and 17 minutes under memory pressure. The helper is terminated at the
 * deadline rather than left holding the model.
 */
internal data class HelperInferenceTimeouts(
    val probe: Duration = 10.seconds,
    val generate: Duration = 60.seconds,
    val count: Duration = 10.seconds,
)
