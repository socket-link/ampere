package link.socket.ampere.link

/**
 * Deterministic, side-effect-free matching of a [LinkRequirement] against the
 * Links a Plug could use.
 *
 * Modelled on [link.socket.ampere.plug.permission.PlugPermissionGate]: pure,
 * never throws, returns a sealed result. Keeping it separate from
 * [LinkResolutionService] means the whole matching policy is testable without a
 * database, a bus, or a coroutine.
 *
 * ## Check precedence
 *
 * A candidate Link is evaluated in a fixed order, and the first failing check
 * wins:
 *
 * 1. **Revocation** — revoked beats everything, mirroring the permission gate's
 *    "revoked beats granted" invariant. A revoked Link must not produce a
 *    scope-shaped error message that sends a reader looking in the wrong place.
 * 2. **Transport capability** — if the platform cannot drive this transport in
 *    the requested role, no amount of direction or scope makes it work.
 * 3. **Direction** — the Link points the wrong way.
 * 4. **Scope** — the Link points the right way but may not carry these types.
 *
 * ## Which Links are candidates
 *
 * A candidate has the requirement's transport and answers to its name. A Link
 * minted for a requirement ([LinkId.forRequirement], so it carries a
 * [Link.requirementName]) answers only a requirement of that name; an opaque
 * Link answers any requirement of its transport. Without the name, a Plug whose
 * own `native_framework:workspace` Link was missing was asked to consent to
 * `native_framework:calendar`, the first Link of the transport in the store,
 * and a grant on another Plug's Link could resolve it.
 *
 * Candidates are tried in a fixed order: the Link minted for this requirement
 * first, then the opaque ones, each group in list order. The first that passes
 * wins. If none passes, the failure reported is the *first candidate's*, so the
 * result is deterministic and names the Plug's own Link when it has one.
 *
 * ## Ungranted versus missing
 *
 * A Link the Plug was never granted is invisible: it cannot satisfy the
 * requirement. It is not, however, absent, and the two are reported apart.
 * [LinkResolutionFailure.UngrantedLink] means a candidate exists that would
 * resolve once granted, and carries its [LinkId] so a caller can ask for consent
 * instead of re-deriving the Link. [LinkResolutionFailure.MissingLink] means no
 * candidate would: none exists, or every one would still fail a check. Granting
 * a Link that `check` rejects records a grant that can never pass, so the gate
 * never asks for one.
 */
object LinkResolutionGate {

    fun resolve(
        requirement: LinkRequirement,
        candidates: List<Link>,
        grants: LinkGrants,
        platform: PlatformTarget,
    ): LinkResolution {
        val matching = candidatesFor(requirement, candidates)

        // A Link the Plug was never granted is not a candidate at all — it is
        // invisible, not rejected. Revoked grants stay visible so the failure
        // can say "revoked" rather than "missing".
        val visible = matching.filter { link ->
            grants.grantFor(link.id) != null
        }

        if (visible.isEmpty()) {
            if (requirement.optional) return LinkResolution.Skipped(requirement)
            return LinkResolution.Failed(
                requirement = requirement,
                failure = nothingVisible(requirement, matching, grants, platform),
            )
        }

        val evaluated = visible.map { link -> link to check(requirement, link, grants, platform) }

        evaluated.firstOrNull { (_, failure) -> failure == null }?.let { (link, _) ->
            return LinkResolution.Resolved(requirement, link)
        }

        return LinkResolution.Failed(
            requirement = requirement,
            failure = evaluated.first().second!!,
        )
    }

    /**
     * The Links that may satisfy [requirement], in the order they are tried:
     * the one minted for it, then the opaque ones of its transport.
     */
    private fun candidatesFor(requirement: LinkRequirement, links: List<Link>): List<Link> {
        val (minted, opaque) = links
            .filter { link -> link.transport == requirement.transport }
            .filter { link -> link.requirementName == null || link.requirementName == requirement.name }
            .partition { link -> link.requirementName != null }
        return minted + opaque
    }

    /**
     * The failure to report when no candidate is visible to this Plug.
     *
     * The first candidate, in [candidatesFor] order, that *would* resolve once
     * granted is the consent question, and the caller gets the [LinkId] to ask
     * about. With no such candidate the requirement is missing, whether nothing
     * answers to it at all or everything that does would still fail a check:
     * there is no Link to name whose grant would help.
     */
    private fun nothingVisible(
        requirement: LinkRequirement,
        matching: List<Link>,
        grants: LinkGrants,
        platform: PlatformTarget,
    ): LinkResolutionFailure {
        val grantable = matching.firstOrNull { link -> check(requirement, link, grants, platform) == null }
            ?: return LinkResolutionFailure.MissingLink(
                requirementName = requirement.name,
                transport = requirement.transport,
                direction = requirement.direction,
            )

        return LinkResolutionFailure.UngrantedLink(
            requirementName = requirement.name,
            linkId = grantable.id,
        )
    }

    /** Null when the Link satisfies the requirement. */
    private fun check(
        requirement: LinkRequirement,
        link: Link,
        grants: LinkGrants,
        platform: PlatformTarget,
    ): LinkResolutionFailure? {
        revocationOf(link, grants)?.let { scope ->
            return LinkResolutionFailure.RevokedCredential(
                requirementName = requirement.name,
                linkId = link.id,
                scope = scope,
            )
        }

        if (!link.transport.capability(platform).permits(requirement.role)) {
            return LinkResolutionFailure.TransportUnsupported(
                requirementName = requirement.name,
                linkId = link.id,
                transport = link.transport,
                platform = platform,
                role = requirement.role,
            )
        }

        if (!link.direction.satisfies(requirement.direction)) {
            return LinkResolutionFailure.DirectionViolation(
                requirementName = requirement.name,
                linkId = link.id,
                required = requirement.direction,
                actual = link.direction,
            )
        }

        val missingScope = requirement.minimumScope - link.scope
        if (missingScope.isNotEmpty()) {
            return LinkResolutionFailure.ScopeViolation(
                requirementName = requirement.name,
                linkId = link.id,
                missingScope = missingScope,
            )
        }

        return null
    }

    /**
     * Widest revocation that applies, or null.
     *
     * Ordered widest-first so the reported blast radius is the true one: if the
     * Link is gone, saying "your grant was revoked" would understate it.
     */
    private fun revocationOf(link: Link, grants: LinkGrants): RevocationScope? = when {
        link.isRevoked -> RevocationScope.LINK
        link.credentialRef?.isRevoked == true -> RevocationScope.CREDENTIAL
        link.folderRef?.isRevoked == true -> RevocationScope.FOLDER
        grants.isRevoked(link.id) -> RevocationScope.PLUG_GRANT
        else -> null
    }

    /**
     * Whether an already-resolved Link permits a chassis operation.
     *
     * Resolution proves the Link *can* satisfy the requirement; this is the
     * per-call check that the specific operation is in-bounds. A write-only
     * Link resolved for an Execute-shaped requirement still rejects Perceive.
     */
    fun permits(link: Link, operation: LinkOperation): Boolean =
        !link.isRevoked &&
            link.credentialRef?.isRevoked != true &&
            link.folderRef?.isRevoked != true &&
            link.direction.permits(operation)
}
