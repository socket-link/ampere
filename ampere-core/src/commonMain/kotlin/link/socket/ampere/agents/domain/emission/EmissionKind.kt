package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The four core kinds of [Emission] surfaced by AMPERE today. The kind is a
 * peer of the payload — a tag — and is preserved on the wire to allow
 * consumers to dispatch without unpacking the payload.
 *
 * Adding a kind is a wire-format change: introduce a new variant, add the
 * matching [EmissionPayload] variant, and bump the concept cells.
 */
@Serializable
sealed interface EmissionKind {

    /** Prose narration intended for human reading. */
    @Serializable
    @SerialName("EmissionKind.Prose")
    data object Prose : EmissionKind

    /** A question with affordances representing distinct choices. */
    @Serializable
    @SerialName("EmissionKind.Decision")
    data object Decision : EmissionKind

    /** A request to confirm an effect before it executes. */
    @Serializable
    @SerialName("EmissionKind.Confirmation")
    data object Confirmation : EmissionKind

    /** An ambient observation or reading (gauge, status, latency, …). */
    @Serializable
    @SerialName("EmissionKind.Sensor")
    data object Sensor : EmissionKind
}
