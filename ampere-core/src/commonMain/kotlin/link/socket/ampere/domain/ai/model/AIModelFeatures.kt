package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable
import link.socket.ampere.domain.tool.ProvidedTool

/**
 * @property supportsSamplingParameters Whether the model accepts non-default
 *   sampling parameters (`temperature`, `top_p`). Models that reject them with
 *   a 400 (Claude Opus 4.7+, Sonnet 5, Fable 5.x; OpenAI reasoning models) set
 *   this to `false`, and Ampere omits those fields from the request.
 *   Gemini 3 and later set it too (AMPR-488): Google accepts the fields but
 *   says a temperature below its default of 1.0 can make those models loop or
 *   degrade, so Ampere leaves them at the provider default.
 * @property defaultThinking The thinking level the model runs at when the
 *   request names none, for a model that takes a `reasoning_effort` from
 *   Ampere. Null means Ampere sends no effort to this model. Gemini 3 and later
 *   set it, and so do OpenAI's GPT-5.5, GPT-5.6 and GPT-6 (AMPR-501). The effort a call sends is decided by
 *   [ReasoningEffortPolicy][link.socket.ampere.agents.domain.reasoning.ReasoningEffortPolicy],
 *   which lowers it from this default when the call's output budget is small.
 */
@Serializable
data class AIModelFeatures(
    val availableTools: List<ProvidedTool<*>>,
    val reasoningLevel: RelativeReasoning,
    val speed: RelativeSpeed,
    val supportedInputs: SupportedInputs,
    val trainingCutoffDate: GMTDate,
    val supportsSamplingParameters: Boolean = true,
    val defaultThinking: ThinkingLevel? = null,
) {
    /**
     * How much a model reasons before it answers, in the order Google's
     * `thinking_level` and OpenAI's `reasoning_effort` share. [MINIMAL] exists
     * because some models default to it (Gemini 3.5 Flash-Lite). Ampere never
     * sends it: Gemini 3.8 Flash and Gemini 4 Argon reject it with an error, and
     * OpenAI's GPT-5.5 onward do not list it.
     */
    @Serializable
    enum class ThinkingLevel {
        MINIMAL, LOW, MEDIUM, HIGH
    }

    @Serializable
    enum class RelativeReasoning {
        LOW, NORMAL, HIGH
    }

    @Serializable
    enum class RelativeSpeed {
        SLOW, NORMAL, FAST
    }

    @Serializable
    data class SupportedInputs(
        val audio: Boolean = false,
        val image: Boolean = false,
        val pdf: Boolean = false,
        val text: Boolean = false,
        val video: Boolean = false,
    ) {
        companion object {
            val ALL = SupportedInputs(
                audio = true,
                image = true,
                pdf = true,
                text = true,
                video = true,
            )

            val TEXT = SupportedInputs(
                text = true,
            )

            val TEXT_AND_IMAGE = SupportedInputs(
                image = true,
                text = true,
            )

            val TEXT_IMAGE_AND_PDF = SupportedInputs(
                image = true,
                text = true,
                pdf = true,
            )
        }
    }
}
