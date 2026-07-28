package link.socket.ampere.link

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonType

/**
 * The binding between a capability and its concrete medium — Socket's data-link
 * layer.
 *
 * A Link is *shared*: one Google OAuth Link serves the Calendar Plug and the
 * Gmail Plug. The user authenticates once; each Plug records its own grant
 * against the same [id], and revoking the Link cascades to every grant that
 * references it. That is why a Link carries no plug identity — it would be a
 * lie about the one-to-many relationship.
 *
 * @property transport The wire. See [Transport] for why capability is
 *   per-platform rather than per-enum-member.
 * @property direction Read, write, or both. Enforced at resolution.
 * @property egress Where data physically goes. The consent surface reads this.
 * @property scope Exactly which canon types may flow through this wire.
 *   Narrower than the transport can technically carry, on purpose.
 * @property credentialRef A *reference*. Raw credentials never live in this
 *   type, never reach a trace, and never cross the bus.
 */
@Serializable
data class Link(
    val id: LinkId,
    val transport: Transport,
    val direction: LinkDirection,
    val egress: EgressClass,
    val scope: Set<CanonType> = emptySet(),
    val credentialRef: CredentialRef? = null,
    val revokedAt: Instant? = null,
) {
    /** A revoked Link resolves for nobody, regardless of standing grants. */
    val isRevoked: Boolean get() = revokedAt != null
}

/**
 * Where data travels when it leaves the Plug.
 *
 * This is the fact a consent sheet is really asking about. "Read your
 * calendar" means something very different for [OnDevice] EventKit than for a
 * [ThirdParty] sync service, and the type keeps the distinction from collapsing
 * into prose.
 */
@Serializable
sealed interface EgressClass {

    /** Never leaves the device. */
    @Serializable
    @SerialName("egress.on_device")
    data object OnDevice : EgressClass

    /** Reaches a Socket-operated service and nothing else. */
    @Serializable
    @SerialName("egress.first_party")
    data object FirstParty : EgressClass

    /** Reaches a named external provider. */
    @Serializable
    @SerialName("egress.third_party")
    data class ThirdParty(val provider: String) : EgressClass
}

/**
 * A pointer to credential material held by the platform keychain.
 *
 * Storage is platform-side and out of scope here; this type exists so a Link
 * can *name* its credentials without ever carrying them. If a future change
 * puts a token string on this class, the Link becomes a secret and every trace
 * that recorded one becomes a breach.
 *
 * @property revokedAt Set when the credential itself is revoked, as distinct
 *   from a Plug's grant being revoked. Both surface as
 *   [LinkResolutionFailure.RevokedCredential]; they differ in blast radius.
 */
@Serializable
data class CredentialRef(
    val keychainAlias: String,
    val revokedAt: Instant? = null,
) {
    val isRevoked: Boolean get() = revokedAt != null
}
