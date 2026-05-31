package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single response option attached to an [Emission].
 *
 * The `signalPayload` is opaque to AMPERE — it travels back out on the bus
 * as part of `EmissionEvent.Resolved` when the human selects this
 * affordance. Surface-side consumers decide what to render and what value
 * to populate; AMPERE only carries the bytes.
 */
@Serializable
data class Affordance(
    val id: AffordanceId,
    val label: String,
    val signalPayload: JsonElement,
)
