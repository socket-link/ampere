package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable
import link.socket.ampere.domain.tool.ProvidedTool

/**
 * @property supportsSamplingParameters Whether the model accepts non-default
 *   sampling parameters (`temperature`, `top_p`). Models that reject them with
 *   a 400 (Claude Opus 4.7+, Sonnet 5, Fable 5.x; OpenAI reasoning models) set
 *   this to `false`, and Ampere omits those fields from the request.
 */
@Serializable
data class AIModelFeatures(
    val availableTools: List<ProvidedTool<*>>,
    val reasoningLevel: RelativeReasoning,
    val speed: RelativeSpeed,
    val supportedInputs: SupportedInputs,
    val trainingCutoffDate: GMTDate,
    val supportsSamplingParameters: Boolean = true,
) {
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
