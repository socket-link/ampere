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
 * When several Links share a transport, every candidate is tried and the first
 * one that passes wins. If none passes, the failure reported is the *first
 * candidate's*, in list order, so the result is deterministic.
 */
object LinkResolutionGate {

    fun resolve(
        requirement: LinkRequirement,
        candidates: List<Link>,
        grants: LinkGrants,
        platform: PlatformTarget,
    ): LinkResolution {
        val matchingTransport = candidates.filter { it.transport == requirement.transport }

        // A Link the Plug was never granted is not a candidate at all — it is
        // invisible, not rejected. Revoked grants stay visible so the failure
        // can say "revoked" rather than "missing".
        val visible = matchingTransport.filter { link ->
            grants.grantFor(link.id) != null
        }

        if (visible.isEmpty()) {
            return if (requirement.optional) {
                LinkResolution.Skipped(requirement)
            } else {
                LinkResolution.Failed(
                    requirement = requirement,
                    failure = LinkResolutionFailure.MissingLink(
                        requirementName = requirement.name,
                        transport = requirement.transport,
                        direction = requirement.direction,
                    ),
                )
            }
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
