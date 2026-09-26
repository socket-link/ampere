package link.socket.ampere.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.ampere.AMPERE_RUNTIME_VERSION
import link.socket.ampere.canon.CanonType
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkRequirement
import link.socket.ampere.link.Transport
import link.socket.ampere.plug.ManifestValidationReason
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission

class PlugBundleValidatorTest {

    private val validator = PlugBundleValidator()

    private fun bundle(
        manifest: PlugManifest = PlugManifest(id = PlugId("x"), name = "X", version = "1.0.0"),
        bundleFormatVersion: Int = CURRENT_BUNDLE_FORMAT_VERSION,
        signature: ByteArray? = null,
        assets: Map<String, ByteArray> = emptyMap(),
        minimumAmpereVersion: String? = null,
    ): PlugBundle = PlugBundle(
        bundleFormatVersion = bundleFormatVersion,
        manifest = manifest,
        assets = assets,
        signature = signature,
        minimumAmpereVersion = minimumAmpereVersion,
    )

    @Test
    fun `well-formed bundle validates and surfaces deduplicated permissions`() {
        val permissions = listOf(
            PlugPermission.NetworkDomain("api.github.com"),
            PlugPermission.NetworkDomain("api.github.com"),
            PlugPermission.MCPServer("mcp://github"),
        )
        val result = validator.validate(
            bundle(
                manifest = PlugManifest(
                    id = PlugId("github-plug"),
                    name = "GitHub",
                    version = "1.0.0",
                    requiredPermissions = permissions,
                ),
            ),
        )

        val ok = assertIs<BundleValidation.Ok>(result)
        assertEquals(
            listOf(
                PlugPermission.NetworkDomain("api.github.com"),
                PlugPermission.MCPServer("mcp://github"),
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
    fun `blank manifest id fails at PlugId construction before the validator ever runs`() {
        assertFailsWith<IllegalArgumentException> {
            PlugId("  ")
        }
    }

    @Test
    fun `blank manifest name is reported`() {
        val result = validator.validate(
            bundle(manifest = PlugManifest(id = PlugId("x"), name = "", version = "1.0.0")),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(listOf("manifest.name is blank."), failed.reasons)
    }

    @Test
    fun `blank manifest version is reported`() {
        val result = validator.validate(
            bundle(manifest = PlugManifest(id = PlugId("x"), name = "x", version = "")),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(listOf("manifest.version is blank."), failed.reasons)
    }

    @Test
    fun `blank permission discriminator field is reported per variant`() {
        val variants: List<Pair<PlugPermission, String>> = listOf(
            PlugPermission.NetworkDomain("") to "host",
            PlugPermission.MCPServer(" ") to "uri",
            PlugPermission.KnowledgeQuery("") to "scope",
            PlugPermission.NativeAction("") to "actionId",
            PlugPermission.LinkAccess(" ") to "linkId",
            PlugPermission.DeviceCapability("") to "capability",
        )

        variants.forEach { (permission, field) ->
            val result = validator.validate(
                bundle(
                    manifest = PlugManifest(
                        id = PlugId("x"),
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
    fun `an unrecognised device capability token does not fail install`() {
        val result = validator.validate(
            bundle(
                manifest = PlugManifest(
                    id = PlugId("exotic-plug"),
                    name = "Exotic",
                    version = "1.0.0",
                    requiredPermissions = listOf(
                        PlugPermission.DeviceCapability("some_future_capability"),
                    ),
                ),
            ),
        )

        val ok = assertIs<BundleValidation.Ok>(result)
        assertEquals(listOf(PlugPermission.DeviceCapability("some_future_capability")), ok.permissions)
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
                manifest = PlugManifest(id = PlugId("x"), name = "", version = ""),
            ),
        )
        val failed = assertIs<BundleValidation.Failed>(result)
        assertTrue(failed.reasons.size >= 3)
    }

    @Test
    fun `a bundle without a version pin validates`() {
        assertIs<BundleValidation.Ok>(validator.validate(bundle(minimumAmpereVersion = null)))
    }

    @Test
    fun `a bundle pinned to this build's own version validates`() {
        assertIs<BundleValidation.Ok>(
            validator.validate(bundle(minimumAmpereVersion = AMPERE_RUNTIME_VERSION)),
        )
    }

    @Test
    fun `a bundle pinned above the host fails with AmpereVersionTooOld`() {
        val result = PlugBundleValidator(hostAmpereVersion = "0.11.0")
            .validate(bundle(minimumAmpereVersion = "0.12.0"))

        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(
            listOf(
                ManifestValidationReason.AmpereVersionTooOld(
                    required = "0.12.0",
                    current = "0.11.0",
                ),
            ),
            failed.manifestReasons,
        )
        assertTrue(failed.reasons.single().contains("upgrade Ampere"))
    }

    @Test
    fun `a bundle pinned below the host validates`() {
        val result = PlugBundleValidator(hostAmpereVersion = "0.15.0")
            .validate(bundle(minimumAmpereVersion = "0.12.0"))

        assertIs<BundleValidation.Ok>(result)
    }

    @Test
    fun `a host on a release candidate of the pinned version validates`() {
        val result = PlugBundleValidator(hostAmpereVersion = "0.16.0-rc.1")
            .validate(bundle(minimumAmpereVersion = "0.16.0"))

        assertIs<BundleValidation.Ok>(result)
    }

    @Test
    fun `a pin that cannot be compared fails with MalformedAmpereVersion`() {
        val result = validator.validate(bundle(minimumAmpereVersion = "latest"))

        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(
            listOf(ManifestValidationReason.MalformedAmpereVersion("latest")),
            failed.manifestReasons,
        )
    }

    @Test
    fun `an undeclared link scope fails at import rather than at first run`() {
        val result = validator.validate(
            bundle(
                manifest = PlugManifest(
                    id = PlugId("calendar-plug"),
                    name = "Calendar",
                    version = "1.0.0",
                    emits = setOf(CanonType.CALENDAR_EVENT),
                    requiredLinks = listOf(
                        LinkRequirement(
                            name = "calendar",
                            transport = Transport.MCP,
                            direction = LinkDirection.READ,
                            minimumScope = setOf(CanonType.TABLE),
                        ),
                    ),
                ),
            ),
        )

        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(
            listOf(
                ManifestValidationReason.UndeclaredCanonScope(
                    requirementName = "calendar",
                    canonType = CanonType.TABLE,
                ),
            ),
            failed.manifestReasons,
        )
        assertTrue(failed.reasons.single().contains("table"))
    }

    @Test
    fun `an mcp dependency with no matching permission fails at import`() {
        val result = validator.validate(
            bundle(
                manifest = PlugManifest(
                    id = PlugId("github-plug"),
                    name = "GitHub",
                    version = "1.0.0",
                    mcpServers = listOf(McpServerDependency(name = "github", uri = "mcp://github")),
                ),
            ),
        )

        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(
            listOf(
                ManifestValidationReason.MissingMcpServerPermission(
                    dependencyName = "github",
                    uri = "mcp://github",
                ),
            ),
            failed.manifestReasons,
        )
    }

    @Test
    fun `bundle-level and manifest-level failures are reported together`() {
        val result = PlugBundleValidator(hostAmpereVersion = "0.11.0").validate(
            bundle(
                manifest = PlugManifest(
                    id = PlugId("x"),
                    name = "",
                    version = "1.0.0",
                    mcpServers = listOf(McpServerDependency(name = "github", uri = "mcp://github")),
                ),
                minimumAmpereVersion = "0.12.0",
            ),
        )

        val failed = assertIs<BundleValidation.Failed>(result)
        assertEquals(2, failed.manifestReasons.size)
        // One bundle-level string plus one line per typed reason.
        assertEquals(3, failed.reasons.size)
        assertTrue(failed.reasons.first().contains("manifest.name is blank"))
    }
}
