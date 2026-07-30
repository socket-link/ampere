package link.socket.ampere.link

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.LinkEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest

/**
 * Resolves a Plug's declared [LinkRequirement]s to concrete [Link]s at Arc
 * execution time, and emits the lifecycle facts on the bus.
 *
 * Resolution is deliberately late. An Arc references Plugs, never Link
 * bindings — that is what keeps the Arc manifest lean and what lets the same
 * Arc run against a different Link (a different account, a different wire) with
 * no edit. The Arc says "I need the Calendar Plug"; the Plug says "I need a
 * read/write Link of kind `Mcp` scoped to `calendar_event`"; this service is
 * where those two statements meet a real credentialed endpoint.
 *
 * Orchestration only: the matching policy lives in the pure
 * [LinkResolutionGate], and all storage goes through [LinkStore]. Nothing here
 * touches the database directly.
 *
 * @param eventBus Optional. Without it the service still resolves; it just goes
 *   unobserved. Mirrors [link.socket.ampere.agents.domain.routing.CognitiveRelayImpl].
 */
class LinkResolutionService(
    private val linkStore: LinkStore,
    private val platform: PlatformTarget,
    private val eventBus: EventSerialBus? = null,
    private val clock: Clock = Clock.System,
) {

    /**
     * Resolve every requirement in [manifest].
     *
     * Fails as a whole if any non-optional requirement fails, carrying *all*
     * failures rather than just the first — a Plug missing three Links should
     * take one round trip to fix, not three.
     *
     * Never throws. A missing Link is a [Result.failure] carrying
     * [LinkResolutionException].
     */
    suspend fun resolve(
        plugId: PlugId,
        manifest: PlugManifest,
        agentId: AgentId? = null,
    ): Result<ResolvedLinks> {
        val links = linkStore.list().getOrElse { return Result.failure(it) }
        val grants = linkStore.grantsForPlug(plugId).getOrElse { return Result.failure(it) }

        val resolutions = manifest.requiredLinks.map { requirement ->
            LinkResolutionGate.resolve(requirement, links, grants, platform)
        }

        // Optional requirements are still *reported* on the bus when they fail —
        // a misconfigured optional Link is worth seeing — but they never block
        // the Plug, which is the whole meaning of optional.
        val failures = resolutions
            .filterIsInstance<LinkResolution.Failed>()
            .filterNot { it.requirement.optional }

        resolutions.forEach { resolution ->
            when (resolution) {
                is LinkResolution.Resolved -> emitResolved(plugId, resolution, agentId)
                is LinkResolution.Failed -> emitFailed(plugId, resolution, agentId)
                is LinkResolution.Skipped -> Unit
            }
        }

        if (failures.isNotEmpty()) {
            return Result.failure(LinkResolutionException(failures.map { it.failure }))
        }

        return Result.success(
            ResolvedLinks(
                plugId = plugId,
                byRequirement = resolutions
                    .filterIsInstance<LinkResolution.Resolved>()
                    .associate { it.requirement.name to it.link },
            ),
        )
    }

    /** Resolve a single requirement. Same rules, one result. */
    suspend fun resolveOne(
        plugId: PlugId,
        requirement: LinkRequirement,
        agentId: AgentId? = null,
    ): Result<LinkResolution> {
        val links = linkStore.list().getOrElse { return Result.failure(it) }
        val grants = linkStore.grantsForPlug(plugId).getOrElse { return Result.failure(it) }

        val resolution = LinkResolutionGate.resolve(requirement, links, grants, platform)

        when (resolution) {
            is LinkResolution.Resolved -> emitResolved(plugId, resolution, agentId)
            is LinkResolution.Failed -> emitFailed(plugId, resolution, agentId)
            is LinkResolution.Skipped -> Unit
        }

        return Result.success(resolution)
    }

    /** Record a Plug's grant on a Link and announce it. */
    suspend fun grant(
        plugId: PlugId,
        linkId: LinkId,
        agentId: AgentId? = null,
    ): Result<Unit> {
        val link = linkStore.get(linkId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(UnknownLinkException(linkId))

        linkStore.grant(plugId, linkId, clock.now()).getOrElse { return Result.failure(it) }

        eventBus?.publish(
            LinkEvent.LinkGranted(
                eventId = generateUUID("link"),
                timestamp = clock.now(),
                eventSource = sourceFor(agentId),
                linkId = linkId,
                plugId = plugId.value,
                transport = link.transport,
            ),
        )

        return Result.success(Unit)
    }

    /**
     * Revoke the Link and cascade to every Plug holding a grant on it.
     *
     * Returns the plug ids that lost access.
     */
    suspend fun revokeLink(
        linkId: LinkId,
        agentId: AgentId? = null,
    ): Result<List<String>> {
        val now = clock.now()
        val affected = linkStore.revokeLink(linkId, now).getOrElse { return Result.failure(it) }

        eventBus?.publish(
            LinkEvent.LinkRevoked(
                eventId = generateUUID("link"),
                timestamp = now,
                eventSource = sourceFor(agentId),
                linkId = linkId,
                scope = RevocationScope.LINK,
                affectedPlugIds = affected,
            ),
        )

        return Result.success(affected)
    }

    /** Revoke one Plug's grant, leaving every other Plug on the Link intact. */
    suspend fun revokeGrant(
        plugId: PlugId,
        linkId: LinkId,
        agentId: AgentId? = null,
    ): Result<Unit> {
        val now = clock.now()
        linkStore.revokeGrant(plugId, linkId, now).getOrElse { return Result.failure(it) }

        eventBus?.publish(
            LinkEvent.LinkRevoked(
                eventId = generateUUID("link"),
                timestamp = now,
                eventSource = sourceFor(agentId),
                linkId = linkId,
                scope = RevocationScope.PLUG_GRANT,
                affectedPlugIds = listOf(plugId.value),
            ),
        )

        return Result.success(Unit)
    }

    private suspend fun emitResolved(
        plugId: PlugId,
        resolution: LinkResolution.Resolved,
        agentId: AgentId?,
    ) {
        eventBus?.publish(
            LinkEvent.LinkResolved(
                eventId = generateUUID("link"),
                timestamp = clock.now(),
                eventSource = sourceFor(agentId),
                linkId = resolution.link.id,
                plugId = plugId.value,
                requirementName = resolution.requirement.name,
                transport = resolution.link.transport,
            ),
        )
    }

    private suspend fun emitFailed(
        plugId: PlugId,
        resolution: LinkResolution.Failed,
        agentId: AgentId?,
    ) {
        eventBus?.publish(
            LinkEvent.LinkResolutionFailed(
                eventId = generateUUID("link"),
                timestamp = clock.now(),
                eventSource = sourceFor(agentId),
                linkId = linkIdOf(resolution.failure),
                plugId = plugId.value,
                failure = resolution.failure,
            ),
        )
    }

    private fun sourceFor(agentId: AgentId?): EventSource =
        agentId?.let { EventSource.Agent(it) } ?: EventSource.Human

    private fun linkIdOf(failure: LinkResolutionFailure): LinkId = when (failure) {
        is LinkResolutionFailure.MissingLink -> LinkEvent.LinkResolutionFailed.NO_LINK
        is LinkResolutionFailure.DirectionViolation -> failure.linkId
        is LinkResolutionFailure.ScopeViolation -> failure.linkId
        is LinkResolutionFailure.RevokedCredential -> failure.linkId
        is LinkResolutionFailure.TransportUnsupported -> failure.linkId
    }
}
