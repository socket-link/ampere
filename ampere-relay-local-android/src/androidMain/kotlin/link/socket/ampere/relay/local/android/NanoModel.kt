package link.socket.ampere.relay.local.android

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * The few things [MlKitLocalInferenceEngine] asks of the on-device model, in types of its
 * own.
 *
 * ML Kit's request and response classes are final, with constructors a caller cannot reach,
 * so the engine's decisions — what reaches the model as instructions, what its status means
 * for routing, what a reply is — are written against this and tested against a fake. The
 * one implementation that touches ML Kit, [MlKitNanoModel], only translates.
 */
internal interface NanoModel : AutoCloseable {

    /** Whether the model is on this device and ready, ML Kit's `checkStatus()`. */
    suspend fun status(): NanoStatus

    /**
     * Whether the model on this device takes a separate system instruction. ML Kit documents
     * system instructions for Gemini Nano V3 and later; an older model gets them folded into
     * the prompt.
     */
    suspend fun systemPromptAvailable(): Boolean

    /** One single-turn generation. */
    suspend fun generate(request: NanoRequest): NanoReply

    /** The model's own count of the input [request] takes, ML Kit's `countTokens()`. */
    suspend fun countTokens(request: NanoRequest): Int
}

/** ML Kit's `FeatureStatus`, as the engine reads it. */
internal enum class NanoStatus {
    /** Downloaded and ready to generate. */
    AVAILABLE,

    /** Supported here, not downloaded. Nothing downloads it until a host asks. */
    DOWNLOADABLE,

    /** Supported here and downloading now. */
    DOWNLOADING,

    /** Not supported on this device, or the device has not fetched the configuration that says it is. */
    UNAVAILABLE,
}

/**
 * One request as the model is handed it.
 *
 * @property systemInstruction Sent as ML Kit's `SystemInstruction`, or null for none.
 * @property prompt The text part.
 * @property maxOutputTokens The output cap, or null to leave ML Kit's default.
 * @property temperature The sampling temperature, or null to leave ML Kit's default.
 */
internal data class NanoRequest(
    val systemInstruction: String?,
    val prompt: String,
    val maxOutputTokens: Int? = null,
    val temperature: Float? = null,
)

/**
 * The first candidate of a reply.
 *
 * @property text The candidate's text, or null when the reply carried no candidate.
 * @property stoppedAtCap Whether the model stopped because it reached the output cap.
 */
internal data class NanoReply(
    val text: String?,
    val stoppedAtCap: Boolean = false,
)

/**
 * [NanoModel] over ML Kit's Prompt API. Translation only: every decision is the engine's.
 *
 * ML Kit's own failures (`GenAiException`, whose `errorCode` says `BUSY`, a quota, a
 * background call, a request too large) pass through unchanged, for the engine to surface.
 */
internal class MlKitNanoModel(private val model: GenerativeModel) : NanoModel {

    override suspend fun status(): NanoStatus =
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }

    override suspend fun systemPromptAvailable(): Boolean = model.isSystemPromptAvailable()

    override suspend fun generate(request: NanoRequest): NanoReply {
        val candidate = model.generateContent(request.toMlKit()).candidates.firstOrNull()
        return NanoReply(
            text = candidate?.text,
            stoppedAtCap = candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS,
        )
    }

    override suspend fun countTokens(request: NanoRequest): Int = model.countTokens(request.toMlKit()).totalTokens

    override fun close() = model.close()

    private fun NanoRequest.toMlKit(): GenerateContentRequest {
        val text = TextPart(prompt)
        // Read into locals first: inside the builder block, `maxOutputTokens` and
        // `temperature` name the builder's own properties, not this request's.
        val cap = maxOutputTokens
        val sampling = temperature
        val options: GenerateContentRequest.Builder.() -> Unit = {
            cap?.let { this.maxOutputTokens = it }
            sampling?.let { this.temperature = it }
        }
        return when (val instruction = systemInstruction) {
            null -> generateContentRequest(text, options)
            else -> generateContentRequest(SystemInstruction(instruction), text, options)
        }
    }
}
