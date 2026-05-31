package link.socket.ampere.agents.domain.emission

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.reasoning.Confidence

/**
 * One moment of computer-initiated human contact — the unit of CHI.
 *
 * An Emission is published as `EmissionEvent.Produced` on the
 * `EventSerialBus`. AMPERE has no opinion on rendering: that is a
 * Socket-side concern. AMPERE owns the noun (this type), the verb (the
 * event family), and the provenance.
 *
 * Construction is the only point at which an Emission is mutated. Once
 * published it must be treated as immutable; in particular, `dedupKey` is
 * fixed at construction. Callers compute `dedupKey` via
 * [computeDedupKey] for effect-bearing kinds (Confirmation today, Action
 * tomorrow) and leave it `null` for kinds that should always render.
 */
@Serializable
data class Emission(
    val id: EmissionId,
    val kind: EmissionKind,
    val payload: EmissionPayload,
    val affordances: List<Affordance> = emptyList(),
    val confidence: Confidence? = null,
    val provenance: EmissionProvenance,
    val dedupKey: String? = null,
    val producedAt: Instant,
)

/**
 * Convenience helper for the dedup contract: returns a content digest for
 * effect-bearing kinds (currently [EmissionKind.Confirmation]) and `null`
 * otherwise. Callers are free to override this default — the helper exists
 * so the common case is one call.
 */
fun Emission.computeDedupKey(): String? = when (kind) {
    is EmissionKind.Confirmation -> inputDigest(payload)
    else -> null
}
