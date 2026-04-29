package link.socket.ampere.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.PluginPermission

class PluginBundleValidatorTest {

    private val validator = PluginBundleValidator()

    private fun bundle(
        manifest: PluginManifest = PluginManifest(id = "x", name = "X", version = "1.0.0"),
        bundleFormatVersion: Int = CURRENT_BUNDLE_FORMAT_VERSION,
        signature: ByteArray? = null,
        assets: Map<String, ByteArray> = emptyMap(),
    ): PluginBundle = PluginBundle(
        bundleFormatVersion = bundleFormatVersion,
        manifest = manifest,
        assets = assets,
        signature = signature,
    )

    @Test
    fun `well-formed bundle validates and surfaces deduplicated permissions`() {
        val permissions = listOf(
            PluginPermission.NetworkDomain("api.github.com"),
            PluginPermission.NetworkDomain("api.github.com"),
            PluginPermission.MCPServer("mcp://github"),
        )
        val result = validator.validate(
            bundle(
                manifest = PluginManifest(
                    id = "github-plugin",
                    name = "GitHub",
                    version = "1.0.0",
                    requiredPermissions = permissions,
                ),
            ),
        )

        val ok = assertIs<BundleValidation.Ok>(result)
        assertEquals(
            listOf(
                PluginPermission.NetworkDomain("api.github.com"),
                PluginPermission.MCPServer("mcp://github"),
            ),
            ok.permissions,
        )
    }

    @Test
    fun `unsupported bundleFormatVersion is reported`() {
        val result = validator.validate(bundle(bundleFormatVersion = 999))
        val failed = assertIs<BundleValidation.Failed>(result)
        assertTrue(failed.reasons.any { it.contains("bundleFormatVersion 999") })
    }

    @Test
    fun `blank manifest id is reported`() {
        val result = validator.validate(
            bundle(manifest = PluginManifest(id = "  ", name = "x", version = "1.0.0")),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(listOf("manifest.id is blank."), failed.reasons)
    }

    @Test
    fun `blank manifest name is reported`() {
        val result = validator.validate(
            bundle(manifest = PluginManifest(id = "x", name = "", version = "1.0.0")),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(listOf("manifest.name is blank."), failed.reasons)
    }

    @Test
    fun `blank manifest version is reported`() {
        val result = validator.validate(
            bundle(manifest = PluginManifest(id = "x", name = "x", version = "")),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(listOf("manifest.version is blank."), failed.reasons)
    }

    @Test
    fun `blank permission discriminator field is reported per variant`() {
        val variants: List<Pair<PluginPermission, String>> = listOf(
            PluginPermission.NetworkDomain("") to "host",
            PluginPermission.MCPServer(" ") to "uri",
            PluginPermission.KnowledgeQuery("") to "scope",
            PluginPermission.NativeAction("") to "actionId",
            PluginPermission.LinkAccess(" ") to "linkId",
        )

        variants.forEach { (permission, field) ->
            val result = validator.validate(
                bundle(
                    manifest = PluginManifest(
                        id = "x",
                        name = "x",
                        version = "1.0.0",
                        requiredPermissions = listOf(permission),
                    ),
                ),
            )
            val failed = assertIs<BundleValidation.Failed>(result)
            assertEquals(1, failed.reasons.size)
            assertTrue(failed.reasons.single().contains(field))
            assertTrue(failed.reasons.single().contains(permission::class.simpleName!!))
        }
    }

    @Test
    fun `empty signature is reported`() {
        val result = validator.validate(bundle(signature = ByteArray(0)))
        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(listOf("signature.sig is present but empty."), failed.reasons)
    }

    @Test
    fun `multiple failures are reported together`() {
        val result = validator.validate(
            bundle(
                bundleFormatVersion = 999,
                manifest = PluginManifest(id = "", name = "", version = ""),
            ),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertTrue(failed.reasons.size >= 4)
    }
}
