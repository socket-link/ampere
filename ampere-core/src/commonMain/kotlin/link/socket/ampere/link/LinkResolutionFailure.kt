package link.socket.ampere.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonType

/**
 * Why a [LinkRequirement] could not be satisfied.
 *
 * The list is closed; callers can rely on `when` being exhaustive. Every
 * variant is `@Serializable` because these travel on the bus as
 * [link.socket.ampere.agents.domain.event.LinkEvent.LinkResolutionFailed] —
 * a resolution that fails silently is worse than one that fails loudly.
 */
@Serializable
sealed interface LinkResolutionFailure {

    val requirementName: String

    /**
     * No Link of the required transport exists at all.
     *
     * A misconfiguration, or a Plug on a platform that has no such wire —
     * there is nothing to grant, so there is no [LinkId] to report. When a
     * Link of the transport *does* exist but this Plug holds no grant on it,
     * the failure is [UngrantedLink] instead.
     */
    @Serializable
    @SerialName("link_failure.missing")
    data class MissingLink(
        override val requirementName: String,
        val transport: Transport,
        val direction: LinkDirection,
    ) : LinkResolutionFailure

    /**
     * A Link of the required transport exists, but this Plug has never been
     * granted it.
     *
     * The consent-shaped failure: unlike every other variant, the remedy is to
     * ask the user rather than to fix a configuration. [linkId] is the Link to
     * request a grant on, so a caller driving just-in-time consent does not
     * have to re-derive it from the [LinkRequirement] and re-query the store.
     *
     * A Plug's *first* use of a seeded Link — a `NATIVE_FRAMEWORK` wire that
     * models "this device has EventKit", say — always lands here.
     */
    @Serializable
    @SerialName("link_failure.ungranted")
    data class UngrantedLink(
        override val requirementName: String,
        val linkId: LinkId,
    ) : LinkResolutionFailure

    /**
     * A Link exists but points the wrong way — the classic case being a Plug
     * asking to Perceive through a write-only push sink.
     */
    @Serializable
    @SerialName("link_failure.direction")
    data class DirectionViolation(
        override val requirementName: String,
        val linkId: LinkId,
        val required: LinkDirection,
        val actual: LinkDirection,
    ) : LinkResolutionFailure

    /** A Link exists but is not permitted to carry every canon type required. */
    @Serializable
    @SerialName("link_failure.scope")
    data class ScopeViolation(
        override val requirementName: String,
        val linkId: LinkId,
        val missingScope: Set<CanonType>,
    ) : LinkResolutionFailure

    /**
     * The Link, its credential, or this Plug's grant on it has been revoked.
     *
     * [scope][RevocationScope] distinguishes the blast radius: a revoked Link
     * takes every Plug with it, a revoked grant takes only this one.
     */
    @Serializable
    @SerialName("link_failure.revoked")
    data class RevokedCredential(
        override val requirementName: String,
        val linkId: LinkId,
        val scope: RevocationScope,
    ) : LinkResolutionFailure

    /**
     * The transport cannot act in the requested role on this platform.
     *
     * The motivating case: a Plug requiring an `AppFunction` Link in
     * `CONSUMER` role resolves on Android and fails here on iOS, because iOS
     * has no AppIntent-consumer path — cross-app orchestration belongs to Siri.
     */
    @Serializable
    @SerialName("link_failure.transport_unsupported")
    data class TransportUnsupported(
        override val requirementName: String,
        val linkId: LinkId,
        val transport: Transport,
        val platform: PlatformTarget,
        val role: TransportRole,
    ) : LinkResolutionFailure
}

/** How far a revocation reaches. */
@Serializable
enum class RevocationScope {

    /** The Link itself is gone. Cascades to every Plug that used it. */
    @SerialName("link")
    LINK,

    /** The stored credential is gone. Cascades to every Plug that used it. */
    @SerialName("credential")
    CREDENTIAL,

    /** The folder mount is gone. Cascades to every Plug that used it. */
    @SerialName("folder")
    FOLDER,

    /** Only this Plug's grant on the Link was revoked. */
    @SerialName("plug_grant")
    PLUG_GRANT,
}

/**
 * Carries one or more [LinkResolutionFailure]s across a `kotlin.Result`
 * boundary.
 *
 * Resolution never throws on its own; this type exists so a typed failure
 * survives `Result.failure`, matching how the rest of the repo handles fallible
 * service calls.
 */
class LinkResolutionException(
    val failures: List<LinkResolutionFailure>,
) : Exception("Link resolution failed: $failures")

/**
 * A [LinkId] was referenced that no [Link] exists for.
 *
 * Distinct from [LinkResolutionFailure.MissingLink], which means "no Link of
 * the required *kind* exists". This one means the caller named a Link that is
 * not in the store at all — a programming error or a
 * dangling reference, not a consent outcome.
 */
class UnknownLinkException(
    val linkId: LinkId,
) : Exception("No Link registered for id '${linkId.value}'")

/**
 * A stored `Links` row exists for [linkId] but this build cannot decode it (AMPR-364).
 *
 * Reachable by version skew alone: `Link.scope` is a `Set<CanonType>` persisted by member name,
 * and an unknown enum member is a hard `SerializationException` — `ignoreUnknownKeys` covers
 * keys, never enum values, and `coerceInputValues` needs a property default that an element of a
 * `Set` does not have.
 *
 * Distinct from [UnknownLinkException]: the row *is* there. A caller that gets this back knows
 * the difference between "you named a Link that does not exist" and "this Link exists and I am
 * too old to read it", which is the difference between a dangling reference and an upgrade.
 *
 * `LinkStore.list()` does not fail this way — it skips the row and says so on the bus. This is
 * what a single-row `get` returns, where there is no rest of the query to save.
 */
class UndecodableLinkException(
    val linkId: LinkId,
    val reason: String,
    cause: Throwable? = null,
) : Exception("Stored Link '${linkId.value}' could not be decoded: $reason", cause)
