package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kind of an [Emission]. The kind is a peer of the payload — a tag — and
 * is preserved on the wire to allow consumers to dispatch without unpacking
 * the payload.
 *
 * Four *core* kinds ([Prose], [Decision], [Confirmation], [Sensor]) are
 * substrate-free and live here. Everything else is a *domain* kind: it rides
 * the one [Domain] variant, which carries the kind's id as data, so a
 * consumer can define a kind in its own package or module without touching
 * this sealed hierarchy. See
 * [link.socket.ampere.agents.domain.emission.kinds.DomainEmissionKind].
 *
 * Adding a *core* kind is a wire-format change: introduce a new variant, add
 * the matching [EmissionPayload] variant, and bump the concept cells. Domain
 * kinds go through [Domain] and cost no wire-format change.
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

    /**
     * A domain kind, identified by a lowercase slug such as `recipe`. Always
     * paired with an [EmissionPayload.Domain] whose `kindId` equals [id];
     * [Emission] enforces the pairing at construction.
     *
     * Consumers do not construct this directly; a
     * [link.socket.ampere.agents.domain.emission.kinds.DomainEmissionKind]
     * descriptor exposes its own `kind`.
     */
    @Serializable
    @SerialName("EmissionKind.Domain")
    data class Domain(val id: String) : EmissionKind {
        init {
            requireDomainKindId(id)
        }
    }
}

/**
 * Format of a domain kind id: a lowercase slug — `[a-z0-9]` segments joined by
 * single hyphens, starting with a letter (`recipe`, `meal-plan`, `shopping-list2`).
 */
private val DOMAIN_KIND_ID_FORMAT = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

/** @throws IllegalArgumentException when [id] is not a lowercase slug. */
internal fun requireDomainKindId(id: String) {
    require(DOMAIN_KIND_ID_FORMAT.matches(id)) {
        "Domain kind id must be a lowercase slug such as 'recipe' or 'meal-plan', got '$id'"
    }
}
