package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable
import link.socket.ampere.domain.tool.ProvidedTool

@Serializable
data class AIModelFeatures(
    val availableTools: List<ProvidedTool<*>>,
    val reasoningLevel: RelativeReasoning,
    val speed: RelativeSpeed,
    val supportedInputs: SupportedInputs,
    val trainingCutoffDate: GMTDate,
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
