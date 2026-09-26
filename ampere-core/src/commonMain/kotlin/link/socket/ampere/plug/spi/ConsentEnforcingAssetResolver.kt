package link.socket.ampere.plug.spi

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.AssetAccessEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.canon.CanonAssetRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.LinkOperation
import link.socket.ampere.link.LinkResolutionGate
import link.socket.ampere.link.LinkStore
import link.socket.ampere.plug.PlugId

/**
 * Wraps an [AssetResolver] with the two contract commitments implementors
 * must not be trusted to remember themselves:
 *
 * 1. **Consent rides the existing ledger for free.** A
 *    [CanonAssetRef.NativeHandle] only resolves while the Link that produced
 *    it, and this Plug's grant on it, are both still standing. A
 *    [CanonAssetRef.Url] has no Link and skips the check entirely. The two
 *    cases are dispatched by an exhaustive `when`, so a third ref variant is a
 *    compile error at the consent decision rather than a silent bypass.
 * 2. **Out-of-band but not invisible.** Every successful resolution records a
 *    lightweight [AssetAccessEvent] through the event door — no payload bytes.
 *
 * Orchestration only, mirroring [link.socket.ampere.link.LinkResolutionService]:
 * the matching policy lives in [LinkResolutionGate], and [eventApi] is
 * optional — without it, resolution still happens, it just goes unrecorded.
 * A persist failure of the access record does not fail the resolution: the
 * door already reports it on the bus as `EventStoreEvent.PersistenceFailed`.
 */
class ConsentEnforcingAssetResolver(
    private val delegate: AssetResolver,
    private val plugId: PlugId,
    private val linkStore: LinkStore,
    private val eventApi: AgentEventApi? = null,
    private val eventSource: EventSource = EventSource.Agent(plugId.value),
    private val clock: Clock = Clock.System,
) : AssetResolver {

    override suspend fun resolve(ref: CanonAssetRef, spec: AssetSpec): Result<AssetBytes> {
        // Exhaustive with no `else` on purpose: a new CanonAssetRef variant has to
        // state whether it carries a consent key, rather than inheriting Url's
        // pass-through silently.
        val linkId: LinkId? = when (ref) {
            // The documented no-consent branch: a URL names no Link, so there is
            // no grant to check and no consent key to record.
            is CanonAssetRef.Url -> null

            is CanonAssetRef.NativeHandle -> {
                val link = linkStore.get(ref.linkId).getOrElse { return Result.failure(it) }
                    ?: return Result.failure(
                        AssetResolutionException(AssetResolutionFailure.LinkNotFound(ref.linkId)),
                    )
                val grants = linkStore.grantsForPlug(plugId).getOrElse { return Result.failure(it) }

                // Resolving an asset is a read, so it reuses Perceive's direction
                // semantics rather than growing a third LinkOperation — a
                // write-only Link still cannot serve up a thumbnail.
                val permitted = grants.isGranted(ref.linkId) &&
                    LinkResolutionGate.permits(link, LinkOperation.PERCEIVE)
                if (!permitted) {
                    return Result.failure(
                        AssetResolutionException(AssetResolutionFailure.ConsentRevoked(ref.linkId)),
                    )
                }

                ref.linkId
            }
        }

        return delegate.resolve(ref, spec).onSuccess { bytes ->
            eventApi?.publish(
                AssetAccessEvent(
                    eventId = generateUUID("asset"),
                    timestamp = clock.now(),
                    eventSource = eventSource,
                    linkId = linkId,
                    plugId = plugId.value,
                    byteCount = bytes.bytes.size.toLong(),
                ),
            )
        }
    }
}
