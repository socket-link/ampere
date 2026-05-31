package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Format applied to an [EmissionPayload.Prose] body. Renderers honour the
 * tag; AMPERE itself never renders.
 */
@Serializable
enum class ProseFormat {
    PLAIN,
    MARKDOWN,
}

/**
 * Danger tier for an [EmissionPayload.Confirmation]. Carries no rendering
 * decision — it expresses the underlying *effect* the human is being asked
 * to confirm. Surface-side policy decides how loud to be.
 */
@Serializable
enum class DangerLevel {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Typed body of an [Emission]. One variant per [EmissionKind]; the pairing
 * is established by callers and validated lightly in [Emission.init].
 *
 * Adding a payload variant is a wire-format change — pick a stable
 * `@SerialName` and never rename it.
 */
@Serializable
sealed interface EmissionPayload {

    /** Prose narration intended for human reading. */
    @Serializable
    @SerialName("EmissionPayload.Prose")
    data class Prose(
        val text: String,
        val format: ProseFormat,
    ) : EmissionPayload

    /**
     * A question framed for the human. Affordances representing the
     * available answers live on [Emission.affordances], not in the payload.
     */
    @Serializable
    @SerialName("EmissionPayload.Decision")
    data class Decision(
        val prompt: String,
        val context: String? = null,
    ) : EmissionPayload

    /**
     * A request to confirm an effect before AMPERE executes it. `preview`
     * is a short renderable summary of the effect (for example, the diff
     * a tool is about to apply).
     */
    @Serializable
    @SerialName("EmissionPayload.Confirmation")
    data class Confirmation(
        val action: String,
        val preview: String? = null,
        val dangerLevel: DangerLevel,
    ) : EmissionPayload

    /**
     * Ambient sensor reading. Optional `refreshUri` lets a renderer pull a
     * fresh value without going back through the bus.
     */
    @Serializable
    @SerialName("EmissionPayload.Sensor")
    data class Sensor(
        val label: String,
        val value: String,
        val unit: String? = null,
        val refreshUri: String? = null,
    ) : EmissionPayload
}
