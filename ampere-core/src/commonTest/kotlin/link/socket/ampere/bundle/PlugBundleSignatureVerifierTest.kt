package link.socket.ampere.bundle

import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest

class PlugBundleSignatureVerifierTest {

    private val bundle = PlugBundle(
        bundleFormatVersion = 1,
        manifest = PlugManifest(id = PlugId("x"), name = "X", version = "1.0.0"),
        assets = emptyMap(),
        signature = byteArrayOf(0x01, 0x02),
    )

    @Test
    fun `default no-op verifier returns Verified Skipped`() = runTest {
        val result = NoOpPlugBundleSignatureVerifier.verify(bundle)
        assertSame(PlugBundleSignatureVerification.Verified.Skipped, result)
    }

    @Test
    fun `default no-op verifier returns Verified Skipped even when signature is absent`() = runTest {
        val unsigned = PlugBundle(
            bundleFormatVersion = 1,
            manifest = PlugManifest(id = PlugId("x"), name = "X", version = "1.0.0"),
            assets = emptyMap(),
            signature = null,
        )
        val result = NoOpPlugBundleSignatureVerifier.verify(unsigned)
        assertSame(PlugBundleSignatureVerification.Verified.Skipped, result)
    }
}
