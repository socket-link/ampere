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
 * lie about the one-to-many relationship. It may carry the name of the
 * requirement it was minted for, in its [id]: see [requirementName].
 *
 * @property transport The wire. See [Transport] for why capability is
 *   per-platform rather than per-enum-member.
 * @property direction Read, write, or both. Enforced at resolution.
 * @property egress Where data physically goes. The consent surface reads this.
 * @property scope Exactly which canon types may flow through this wire.
 *   Narrower than the transport can technically carry, on purpose.
 * @property credentialRef A *reference*. Raw credentials never live in this
 *   type, never reach a trace, and never cross the bus.
 * @property folderRef A *reference* to a mounted folder, e.g. a security-scoped
 *   bookmark. Distinct from [credentialRef]: a folder mount is not a secret,
 *   so it belongs to a different storage class and a different row in the
 *   consent ledger.
 */
@Serializable
data class Link(
    val id: LinkId,
    val transport: Transport,
    val direction: LinkDirection,
    val egress: EgressClass,
    val scope: Set<CanonType> = emptySet(),
    val credentialRef: CredentialRef? = null,
    val folderRef: FolderRef? = null,
    val revokedAt: Instant? = null,
) {
    /** A revoked Link resolves for nobody, regardless of standing grants. */
    val isRevoked: Boolean get() = revokedAt != null

    /**
     * The [LinkRequirement.name] this Link was minted for, read out of an [id]
     * of the [LinkId.forRequirement] form for its own [transport], or null for
     * an opaque id.
     *
     * Derived rather than stored, so every Link already persisted in that form
     * is named without a migration or a re-seed.
     */
    val requirementName: String?
        get() = id.value
            .removePrefix(LinkId.prefixOf(transport))
            .takeIf { name -> name.length < id.value.length && name.isNotEmpty() }
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
 * A Link Ampere mints takes its alias from [forLink]: `ampere.link:<link id>`.
 * The persistent MCP credential binding derives the same alias from the
 * [LinkId] it is handed, so the alias is computable without a lookup table
 * and two Links cannot share one (AMPR-464).
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

    companion object {

        /** What every alias under the convention starts with. */
        const val ALIAS_PREFIX: String = "ampere.link:"

        /**
         * The alias for [linkId]'s credentials: [ALIAS_PREFIX], then the id
         * verbatim. The prefix is fixed and the id is the Link's identity, so
         * distinct Links get distinct aliases. `mcp:work-source` becomes
         * `ampere.link:mcp:work-source`.
         */
        fun aliasFor(linkId: LinkId): String = ALIAS_PREFIX + linkId.value

        /** A reference to [linkId]'s credentials under the alias convention. */
        fun forLink(linkId: LinkId): CredentialRef = CredentialRef(keychainAlias = aliasFor(linkId))
    }
}

/**
 * A pointer to a mounted folder, e.g. a security-scoped bookmark.
 *
 * A folder mount is not a secret — it is an opaque path token, useless to an
 * attacker without the app's entitlements — so it does not belong in the
 * keychain-backed [CredentialRef] seam, and presenting it as a credential in
 * the consent ledger would misrepresent the Link in exactly the surface where
 * clarity matters most. Storage of the bookmark bytes themselves is
 * platform-side and out of scope here; this type exists so a Link can *name*
 * its folder mount without carrying it.
 *
 * @property mountId The key under which the platform-side bookmark blob is
 *   stored, e.g. in an app-group-shared folder mount store.
 * @property revokedAt Set when the folder mount itself is revoked, as
 *   distinct from a Plug's grant being revoked.
 */
@Serializable
data class FolderRef(
    val mountId: String,
    val revokedAt: Instant? = null,
) {
    val isRevoked: Boolean get() = revokedAt != null
}
