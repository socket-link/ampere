package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.AssetAccessEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.canon.CanonAssetRef
import link.socket.ampere.canon.CanonType
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.EgressClass
import link.socket.ampere.link.InMemoryLinkStore
import link.socket.ampere.link.Link
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.Transport
import link.socket.ampere.plug.PlugId

/**
 * The access-event half of [ConsentEnforcingAssetResolver]: every successful resolution is
 * recorded through the door, so it is both persisted and delivered to bus subscribers, and a
 * refused one leaves no record at all (AMPR-340). Consent gating itself is covered by
 * `ConsentEnforcingAssetResolverTest` in commonTest.
 */
class ConsentEnforcingAssetResolverEventTest {

    private val photosLink = Link(
        id = LinkId("photos-library"),
        transport = Transport.NATIVE_FRAMEWORK,
        direction = LinkDirection.READ,
        egress = EgressClass.OnDevice,
        scope = setOf(CanonType.PHOTO),
        credentialRef = CredentialRef("keychain://photos"),
    )

    private val plugId = PlugId("photos-plug")

    private val handle = CanonAssetRef.NativeHandle(linkId = photosLink.id, nativeId = "PHAsset/abc123")

    private val stubBytes = AssetBytes(bytes = byteArrayOf(1, 2, 3), mimeType = "image/jpeg")

    private class StubResolver(private val result: Result<AssetBytes>) : AssetResolver {
        override suspend fun resolve(ref: CanonAssetRef, spec: AssetSpec): Result<AssetBytes> = result
    }

    @Test
    fun `a successful resolution records an access event with no payload bytes`() = runBlocking {
        coroutineScope {
            InMemoryEventApi.open(agentId = plugId.value, scope = this).use { door ->
                val received = CompletableDeferred<AssetAccessEvent>()

                door.bus.subscribe<AssetAccessEvent, EventSubscription.ByEventClassType>(
                    agentId = "observer",
                    eventType = AssetAccessEvent.EVENT_TYPE,
                ) { event, _ ->
                    if (!received.isCompleted) received.complete(event)
                }

                val store = InMemoryLinkStore(listOf(photosLink))
                store.grant(plugId, photosLink.id, Instant.fromEpochMilliseconds(1))
                val delegate = StubResolver(Result.success(stubBytes))
                val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store, eventApi = door.api)

                resolver.resolve(handle, AssetSpec()).getOrThrow()

                val seen = withTimeout(5.seconds) { received.await() }
                assertEquals(photosLink.id, seen.linkId)
                assertEquals(plugId.value, seen.plugId)
                assertEquals(stubBytes.bytes.size.toLong(), seen.byteCount)

                // Through the door means persisted, not just dispatched (F1).
                val stored = door.repository.getAllEvents().getOrThrow()
                assertEquals(listOf(seen.eventId), stored.map { it.eventId })
            }
        }
    }

    @Test
    fun `a refused resolution records nothing`() = runBlocking {
        coroutineScope {
            InMemoryEventApi.open(agentId = plugId.value, scope = this).use { door ->
                var eventCount = 0

                door.bus.subscribe<AssetAccessEvent, EventSubscription.ByEventClassType>(
                    agentId = "observer",
                    eventType = AssetAccessEvent.EVENT_TYPE,
                ) { _, _ -> eventCount++ }

                val store = InMemoryLinkStore()
                val delegate = StubResolver(Result.success(stubBytes))
                val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store, eventApi = door.api)

                resolver.resolve(handle, AssetSpec())

                assertEquals(0, eventCount)
                assertEquals(0, door.repository.getAllEvents().getOrThrow().size)
            }
        }
    }
}
