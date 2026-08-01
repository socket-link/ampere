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

    /** No Link of the required transport is available and granted to this Plug. */
    @Serializable
    @SerialName("link_failure.missing")
    data class MissingLink(
        override val requirementName: String,
        val transport: Transport,
        val direction: LinkDirection,
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
 * the required *kind* is available to this Plug". This one means the caller
 * named a Link that is not in the store at all — a programming error or a
 * dangling reference, not a consent outcome.
 */
class UnknownLinkException(
    val linkId: LinkId,
) : Exception("No Link registered for id '${linkId.value}'")
