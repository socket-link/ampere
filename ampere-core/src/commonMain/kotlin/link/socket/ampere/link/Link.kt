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
 *
 * The set is closed and each member is a wire value: a stored `Links.link_json`
 * row names its member by `@SerialName`. A reader that does not know a member
 * cannot decode the row and says so (`SqlDelightLinkStore` skips it from a list
 * and announces a `StoreRowUndecodableEvent`; a `get` is an
 * `UndecodableLinkException`). It never falls back to another member, because a
 * wrong egress is a false statement on a consent sheet. A consumer's exhaustive
 * `when` over this type stops compiling when a member is added, which is the
 * intended prompt to write the new member's consent copy.
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

    /**
     * Reaches an app the person runs, and from there wherever that app decides
     * (AMPR-517).
     *
     * For a Link to an orchestrating app: one that takes work over the Link and
     * forwards it to a model provider of its own choosing. The host picked the
     * first hop, [app], and nothing after it. The app may route to any provider
     * it has enabled, may hand part of the work to a second one, and may be
     * reconfigured by the person while a run is in flight; a Link cannot bind
     * any of that.
     *
     * What the member claims: data sent through this Link reaches [app], and
     * [app] governs where it goes next. What it does not claim, and what a
     * consent surface built from it must not say: that the data stays on the
     * device (the first hop being local does not make this [OnDevice]), or
     * which provider finally receives it. The truthful sentence is of the form
     * "[app] decides where this goes; choose your providers there".
     *
     * It carries no requested provider, on purpose. A host may ask the app for
     * one when it starts a run, but that is a fact about the run: it differs
     * between runs over the same Link, the app is free to depart from it, and a
     * Link is shared and long-lived. Stored here it would be a stale claim on
     * every later run and would read as a destination. A host that wants to say
     * "started on X; the app may route elsewhere" takes X from its own record
     * of the run.
     *
     * Declaring this member enforces nothing. It lets a Link tell the truth
     * about a destination it does not control.
     *
     * @property app The app that decides, named as loosely as
     *   [ThirdParty.provider] names a provider. A label for the consent
     *   surface, not an identifier anything resolves.
     */
    @Serializable
    @SerialName("egress.externally_governed")
    data class ExternallyGoverned(val app: String) : EgressClass
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
