package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
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
 * Consent gating. The access-event cases live in jvmTest
 * (`ConsentEnforcingAssetResolverEventTest`): since AMPR-340 the resolver records through an
 * [link.socket.ampere.agents.events.api.AgentEventApi] door, which needs a store.
 */
class ConsentEnforcingAssetResolverTest {

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
        var callCount = 0
            private set

        override suspend fun resolve(ref: CanonAssetRef, spec: AssetSpec): Result<AssetBytes> {
            callCount++
            return result
        }
    }

    @Test
    fun `a URL ref has no Link so it resolves without a consent check`() = runTest {
        val store = InMemoryLinkStore()
        val delegate = StubResolver(Result.success(stubBytes))
        val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store)

        val result = resolver.resolve(CanonAssetRef.Url(template = "https://img.example/{w}x{h}.jpg"), AssetSpec())

        assertTrue(result.isSuccess)
        assertEquals(1, delegate.callCount)
    }

    @Test
    fun `a NativeHandle with a valid grant resolves`() = runTest {
        val store = InMemoryLinkStore(listOf(photosLink))
        store.grant(plugId, photosLink.id, Instant.fromEpochMilliseconds(1))
        val delegate = StubResolver(Result.success(stubBytes))
        val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store)

        val result = resolver.resolve(handle, AssetSpec())

        assertEquals(stubBytes, result.getOrThrow())
        assertEquals(1, delegate.callCount)
    }

    @Test
    fun `no Link registered for the handle's linkId refuses without calling the delegate`() = runTest {
        val store = InMemoryLinkStore()
        val delegate = StubResolver(Result.success(stubBytes))
        val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store)

        val result = resolver.resolve(handle, AssetSpec())

        val error = assertIs<AssetResolutionException>(result.exceptionOrNull())
        assertIs<AssetResolutionFailure.LinkNotFound>(error.failure)
        assertEquals(0, delegate.callCount)
    }

    @Test
    fun `a revoked Link refuses resolution at the SPI level`() = runTest {
        val store = InMemoryLinkStore(listOf(photosLink))
        store.grant(plugId, photosLink.id, Instant.fromEpochMilliseconds(1))
        store.upsert(photosLink.copy(revokedAt = Instant.fromEpochMilliseconds(2)))
        val delegate = StubResolver(Result.success(stubBytes))
        val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store)

        val result = resolver.resolve(handle, AssetSpec())

        val error = assertIs<AssetResolutionException>(result.exceptionOrNull())
        assertIs<AssetResolutionFailure.ConsentRevoked>(error.failure)
        assertEquals(0, delegate.callCount)
    }

    @Test
    fun `a revoked grant refuses resolution even though the Link itself is fine`() = runTest {
        val store = InMemoryLinkStore(listOf(photosLink))
        store.grant(plugId, photosLink.id, Instant.fromEpochMilliseconds(1))
        store.revokeGrant(plugId, photosLink.id, Instant.fromEpochMilliseconds(2))
        val delegate = StubResolver(Result.success(stubBytes))
        val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store)

        val result = resolver.resolve(handle, AssetSpec())

        val error = assertIs<AssetResolutionException>(result.exceptionOrNull())
        assertIs<AssetResolutionFailure.ConsentRevoked>(error.failure)
        assertEquals(0, delegate.callCount)
    }

    /**
     * The consent decision each [CanonAssetRef] variant must reach, named so a
     * third variant cannot be added without stating which one it takes.
     */
    private enum class ConsentDecision {
        /** No consent key, so the delegate is reached with no grant lookup. */
        RESOLVES_UNCHECKED,

        /** Carries a consent key; a revoked grant refuses before the delegate. */
        REFUSED_AS_REVOKED,
    }

    /**
     * Same shape as `CanonAssetRefSerializationTest.refSamples()`: one sample per
     * variant, keyed by its pinned discriminator.
     */
    private fun refSamples(): Map<String, CanonAssetRef> = mapOf(
        "canon_asset_ref.url" to CanonAssetRef.Url(template = "https://img.example/{w}x{h}.jpg"),
        "canon_asset_ref.native_handle" to handle,
    )

    // Exhaustive with no `else`, mirroring the resolver: adding a CanonAssetRef
    // variant breaks this test's compilation at the decision it has to make.
    private fun expectedDecision(ref: CanonAssetRef): ConsentDecision = when (ref) {
        is CanonAssetRef.Url -> ConsentDecision.RESOLVES_UNCHECKED
        is CanonAssetRef.NativeHandle -> ConsentDecision.REFUSED_AS_REVOKED
    }

    @Test
    fun `every CanonAssetRef variant reaches its named consent decision under a revoked grant`() = runTest {
        refSamples().forEach { (discriminator, ref) ->
            val store = InMemoryLinkStore(listOf(photosLink))
            store.grant(plugId, photosLink.id, Instant.fromEpochMilliseconds(1))
            store.revokeGrant(plugId, photosLink.id, Instant.fromEpochMilliseconds(2))
            val delegate = StubResolver(Result.success(stubBytes))
            val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store)

            val result = resolver.resolve(ref, AssetSpec())

            when (expectedDecision(ref)) {
                ConsentDecision.RESOLVES_UNCHECKED -> {
                    assertTrue(result.isSuccess, "$discriminator should resolve without a consent check")
                    assertEquals(1, delegate.callCount, "$discriminator should reach the delegate")
                }

                ConsentDecision.REFUSED_AS_REVOKED -> {
                    val error = assertIs<AssetResolutionException>(
                        result.exceptionOrNull(),
                        "$discriminator should be refused under a revoked grant",
                    )
                    assertIs<AssetResolutionFailure.ConsentRevoked>(
                        error.failure,
                        "$discriminator should fail as ConsentRevoked",
                    )
                    assertEquals(0, delegate.callCount, "$discriminator should not reach the delegate")
                }
            }
        }
    }

    @Test
    fun `resolution works with no door wired`() = runTest {
        val store = InMemoryLinkStore(listOf(photosLink))
        store.grant(plugId, photosLink.id, Instant.fromEpochMilliseconds(1))
        val delegate = StubResolver(Result.success(stubBytes))
        val resolver = ConsentEnforcingAssetResolver(delegate, plugId, store, eventApi = null)

        assertTrue(resolver.resolve(handle, AssetSpec()).isSuccess)
    }
}
