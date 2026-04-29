package link.socket.ampere.bundle

import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import link.socket.ampere.plugin.PluginManifest

class PluginBundleSignatureVerifierTest {

    private val bundle = PluginBundle(
        bundleFormatVersion = 1,
        manifest = PluginManifest(id = "x", name = "X", version = "1.0.0"),
        assets = emptyMap(),
        signature = byteArrayOf(0x01, 0x02),
    )

    @Test
    fun `default no-op verifier returns Verified Skipped`() = runTest {
        val result = NoOpPluginBundleSignatureVerifier.verify(bundle)
        assertSame(PluginBundleSignatureVerification.Verified.Skipped, result)
    }

    @Test
    fun `default no-op verifier returns Verified Skipped even when signature is absent`() = runTest {
        val unsigned = PluginBundle(
            bundleFormatVersion = 1,
            manifest = PluginManifest(id = "x", name = "X", version = "1.0.0"),
            assets = emptyMap(),
            signature = null,
        )
        val result = NoOpPluginBundleSignatureVerifier.verify(unsigned)
        assertSame(PluginBundleSignatureVerification.Verified.Skipped, result)
    }
}
