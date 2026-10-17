package link.socket.ampere.cli.ondevice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The versioned stdio wire between this JVM and `ampere-helper`, the Swift process that reaches
 * Apple's on-device model (AMPR-375). `ampere-cli/macos-helper/Sources/AmpereHelper/Protocol.swift`
 * is the other half. The two are kept in step by hand, which is what [Op.PROBE]'s version range
 * exists to make safe.
 *
 * One JSON object per line each way:
 *
 * | shape | keys | sent by |
 * |---|---|---|
 * | request | `v`, `id`, `op`, `params?` | this JVM |
 * | reply | `v`, `id`, `ok`, `result?` or `error?` | the helper |
 *
 * The frame shape, the op names, the error codes and the availability tokens are those of
 * Socket's `socket-helper`, which serves the same model to Socket's desktop app (SCKT-853), and
 * `ampere-helper` serves every inference op that one does, so Socket's engine runs unchanged
 * against it. The reverse is not true: Socket's helper reads only `prompt`, so a request sent by
 * [HelperLocalInferenceEngine] would lose its instructions and output cap there, and it answers
 * [Op.INFERENCE_COUNT] with `unknown_op`, which reads as "cannot count".
 *
 * ## Versioning
 *
 * [VERSION] is what this build speaks. [Op.PROBE] is answered whatever version asked, and its
 * reply carries the range the helper speaks. A helper whose range excludes [VERSION], or that does
 * not list [INFERENCE_SERVICE], is reported as unavailable with a reason and is sent nothing else.
 * Add a field or an op without bumping; change what one means, or remove it, with a bump.
 */
internal object AmpereHelperWire {
    const val VERSION: Int = 1

    /** The service [ProbeResult.services] must list before anything but [Op.PROBE] is sent. */
    const val INFERENCE_SERVICE: String = "inference"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** One table, kept in step by hand with `Wire.Op` in `Protocol.swift`. */
    object Op {
        const val PROBE: String = "probe"
        const val INFERENCE_PROBE: String = "inference.probe"
        const val INFERENCE_GENERATE: String = "inference.generate"
        const val INFERENCE_COUNT: String = "inference.count"
    }

    object ErrorCode {
        const val UNSUPPORTED_PROTOCOL: String = "unsupported_protocol"
        const val UNKNOWN_OP: String = "unknown_op"
        const val MALFORMED_REQUEST: String = "malformed_request"
        const val PLATFORM_REFUSED: String = "platform_refused"

        /** FoundationModels took the request and did not finish it. The message is the framework's. */
        const val GENERATION_FAILED: String = "generation_failed"
    }

    /**
     * `SystemLanguageModel.default.availability` as the helper spells it. One this build does not
     * know reads as [UNAVAILABLE], so a case a newer helper adds degrades rather than breaks.
     */
    object Availability {
        const val AVAILABLE: String = "available"
        const val DEVICE_NOT_ELIGIBLE: String = "deviceNotEligible"
        const val APPLE_INTELLIGENCE_NOT_ENABLED: String = "appleIntelligenceNotEnabled"
        const val MODEL_NOT_READY: String = "modelNotReady"
        const val UNAVAILABLE: String = "unavailable"
        const val OS_TOO_OLD: String = "osTooOld"
    }
}

@Serializable
internal data class HelperRequest(
    val v: Int,
    val id: String,
    val op: String,
    val params: JsonElement? = null,
)

/**
 * Anything the helper printed. Every field is optional because this is the one type that reads a
 * line before anyone knows what it is.
 */
@Serializable
internal data class HelperFrame(
    val v: Int? = null,
    val id: String? = null,
    val ok: Boolean? = null,
    val result: JsonElement? = null,
    val error: HelperWireError? = null,
)

@Serializable
internal data class HelperWireError(
    val code: String,
    val message: String,
)

/** What `probe` says about the binary: the protocol range it speaks and the services it serves. */
@Serializable
internal data class ProbeResult(
    val protocolMin: Int,
    val protocolMax: Int,
    val build: String = "",
    val services: List<String> = emptyList(),
) {
    fun speaks(version: Int): Boolean = version in protocolMin..protocolMax
}

/** `inference.probe`'s reply. [contextSize] is present only when [availability] is `available`. */
@Serializable
internal data class InferenceProbeResult(
    val availability: String,
    val contextSize: Int? = null,
)

/**
 * `inference.generate`'s params: a request's parts kept apart, as the iOS engine passes them to the
 * framework (AMPR-462). [prompt] is the user's turn and [instructions] the system message; a caller
 * with one flattened string sends it as [prompt] alone. The optional fields are left out of the
 * frame when null.
 */
@Serializable
internal data class InferenceGenerateParams(
    val prompt: String,
    val instructions: String? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
)

@Serializable
internal data class InferenceGenerateResult(
    val text: String,
)

/**
 * `inference.count`'s params, in one of two forms: [text] alone counts one string; [prompt] with
 * [instructions] counts what a session for that request starts from, which is what the model bills
 * (AMPR-462).
 */
@Serializable
internal data class InferenceCountParams(
    val text: String? = null,
    val prompt: String? = null,
    val instructions: String? = null,
)

/** `inference.count`'s reply. A null [tokens] is a helper on a system that cannot count. */
@Serializable
internal data class InferenceCountResult(
    val tokens: Int? = null,
)
