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
 *
 * Construction also checks the kind/payload pairing for domain kinds: an
 * [EmissionKind.Domain] must carry an [EmissionPayload.Domain] with the same
 * id, and vice versa. Core kinds are not checked (see [EmissionPayload]).
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
    /**
     * Ordered surface-delivery intent, highest priority first. Empty means
     * "no opinion" — the host's `SurfacePolicy` falls back to its own
     * default (typically [Surface.Console]). AMPERE never interprets this
     * list itself; it only carries it for the host to consult.
     */
    val surfaces: List<Surface> = emptyList(),
    /**
     * Browser-openable link a [SurfacePolicy] resolution can carry alongside
     * [Surface.Push] for renderers without a live push transport. Mirrors
     * [link.socket.ampere.pause.AgentPause.fallbackUrl].
     */
    val fallbackUrl: String? = null,
) {
    init {
        val domainKind = kind as? EmissionKind.Domain
        val domainPayload = payload as? EmissionPayload.Domain
        when {
            domainKind != null && domainPayload == null ->
                throw IllegalArgumentException(
                    "EmissionKind.Domain('${domainKind.id}') must carry an EmissionPayload.Domain, " +
                        "got ${payload::class.simpleName}",
                )
            domainKind == null && domainPayload != null ->
                throw IllegalArgumentException(
                    "EmissionPayload.Domain('${domainPayload.kindId}') must be paired with EmissionKind.Domain, " +
                        "got ${kind::class.simpleName}",
                )
            domainKind != null && domainPayload != null ->
                require(domainKind.id == domainPayload.kindId) {
                    "EmissionKind.Domain('${domainKind.id}') paired with " +
                        "EmissionPayload.Domain('${domainPayload.kindId}')"
                }
        }
    }
}

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
