package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
 * Typed body of an [Emission]. One variant per [EmissionKind]. The pairing is
 * established by callers; `Emission`'s `init` enforces it for [Domain] only
 * (a `Domain` kind must carry a `Domain` payload with the same id, and vice
 * versa). The four core kinds are not checked, so existing producers are
 * untouched.
 *
 * Adding a *core* payload variant is a wire-format change — pick a stable
 * `@SerialName` and never rename it. Domain kinds ride the one [Domain]
 * variant and need no new core variant.
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

    /**
     * The body of a domain kind: a typed payload that lives outside this sealed
     * hierarchy, carried as data. `body` is the typed value encoded with the
     * kind's own serializer; `fallbackText` is what text-only surfaces (console,
     * voice, digest) show, and what a consumer that does not know [kindId]
     * shows instead of skipping the Emission.
     *
     * Build one through
     * [link.socket.ampere.agents.domain.emission.kinds.DomainEmissionKind.encode]
     * rather than by hand, so `body` is encoded canonically and the digest is
     * stable.
     *
     * @property kindId the domain kind's lowercase slug; equals the paired
     *   [EmissionKind.Domain.id].
     * @property schemaVersion the version of the kind's body schema, `>= 1`. A
     *   consumer whose descriptor is older than this version does not decode the
     *   body and shows [fallbackText].
     * @property body the typed payload, encoded with the kind's own serializer.
     * @property fallbackText human-readable prose, never JSON. Required and
     *   never blank.
     */
    @Serializable
    @SerialName("EmissionPayload.Domain")
    data class Domain(
        val kindId: String,
        val schemaVersion: Int,
        val body: JsonElement,
        val fallbackText: String,
    ) : EmissionPayload {
        init {
            requireDomainKindId(kindId)
            require(schemaVersion >= 1) { "schemaVersion must be >= 1, got $schemaVersion" }
            require(fallbackText.isNotBlank()) { "fallbackText must not be blank for domain kind '$kindId'" }
        }
    }
}
