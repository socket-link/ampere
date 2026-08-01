package link.socket.ampere.plug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.table.TableWriteCapability
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkRequirement
import link.socket.ampere.link.Transport
import link.socket.ampere.plug.permission.PlugPermission

class PlugManifestValidationTest {

    @Test
    fun `manifest with declared mcp server and matching permission validates`() {
        val manifest = PlugManifest(
            id = PlugId("github-plug"),
            name = "GitHub Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.MCPServer("mcp://github"),
            ),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                ),
            ),
        )

        val result = PlugManifestValidator.validate(manifest)

        assertEquals(ManifestValidationResult.Valid, result)
    }

    @Test
    fun `manifest missing matching mcp permission fails with diagnostic naming the uri`() {
        val manifest = PlugManifest(
            id = PlugId("github-plug"),
            name = "GitHub Plug",
            version = "1.0.0",
            requiredPermissions = emptyList(),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                ),
            ),
        )

        val result = PlugManifestValidator.validate(manifest)
        val invalid = assertIs<ManifestValidationResult.Invalid>(result)

        val missing = invalid.reasons.filterIsInstance<ManifestValidationReason.MissingMcpServerPermission>()
        assertEquals(1, missing.size)
        assertEquals("mcp://github", missing.single().uri)
        assertEquals("github", missing.single().dependencyName)
    }

    @Test
    fun `dependency permission not lifted to manifest is flagged`() {
        val knowledgeQuery = PlugPermission.KnowledgeQuery("workspace")
        val manifest = PlugManifest(
            id = PlugId("github-plug"),
            name = "GitHub Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.MCPServer("mcp://github"),
            ),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                    requiredPermissions = listOf(knowledgeQuery),
                ),
            ),
        )

        val result = PlugManifestValidator.validate(manifest)
        val invalid = assertIs<ManifestValidationResult.Invalid>(result)

        val notLifted = invalid.reasons
            .filterIsInstance<ManifestValidationReason.DependencyPermissionNotLifted>()
        assertEquals(1, notLifted.size)
        assertEquals(knowledgeQuery, notLifted.single().permission)
        assertEquals("github", notLifted.single().dependencyName)
    }

    @Test
    fun `manifest with no mcp servers validates regardless of permissions`() {
        val manifest = PlugManifest(
            id = PlugId("no-mcp-plug"),
            name = "No MCP Plug",
            version = "1.0.0",
        )

        val result = PlugManifestValidator.validate(manifest)

        assertEquals(ManifestValidationResult.Valid, result)
    }

    @Test
    fun `multiple mcp servers all require their own permissions`() {
        val manifest = PlugManifest(
            id = PlugId("multi-mcp"),
            name = "Multi MCP",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.MCPServer("mcp://a"),
            ),
            mcpServers = listOf(
                McpServerDependency(name = "a", uri = "mcp://a"),
                McpServerDependency(name = "b", uri = "mcp://b"),
            ),
        )

        val result = PlugManifestValidator.validate(manifest)
        val invalid = assertIs<ManifestValidationResult.Invalid>(result)

        val missingUris = invalid.reasons
            .filterIsInstance<ManifestValidationReason.MissingMcpServerPermission>()
            .map { it.uri }
        assertTrue("mcp://b" in missingUris)
        assertTrue("mcp://a" !in missingUris)
    }

    // -----------------------------------------------------------------
    // Link requirements
    // -----------------------------------------------------------------

    private fun linkRequirement(
        name: String,
        scope: Set<CanonType> = setOf(CanonType.CALENDAR_EVENT),
    ) = LinkRequirement(
        name = name,
        transport = Transport.OAUTH_REST,
        direction = LinkDirection.READ,
        minimumScope = scope,
    )

    @Test
    fun `a manifest declaring well-formed link requirements validates`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(
                linkRequirement("calendar"),
                linkRequirement("mail", setOf(CanonType.EMAIL_MESSAGE)),
            ),
            emits = setOf(CanonType.CALENDAR_EVENT, CanonType.EMAIL_MESSAGE),
            consumes = setOf(CanonType.PERSON),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `two link requirements sharing a name are rejected`() {
        // Both would collapse onto one key in ResolvedLinks, so one Link would
        // be silently unreachable at execution time.
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(
                linkRequirement("calendar"),
                linkRequirement("calendar", setOf(CanonType.EMAIL_MESSAGE)),
            ),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        assertEquals(
            listOf("calendar"),
            invalid.reasons
                .filterIsInstance<ManifestValidationReason.DuplicateLinkRequirementName>()
                .map { it.name },
        )
    }

    @Test
    fun `a link requirement with an empty scope is rejected`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("calendar", emptySet())),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        assertEquals(
            listOf("calendar"),
            invalid.reasons
                .filterIsInstance<ManifestValidationReason.EmptyLinkRequirementScope>()
                .map { it.name },
        )
    }

    // -----------------------------------------------------------------
    // Device capabilities
    // -----------------------------------------------------------------

    @Test
    fun `a manifest declaring a device capability validates`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.DeviceCapability("calendar"),
            ),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a manifest declaring an unrecognised device capability still validates`() {
        // The validator only enforces structural rules; an unrecognised
        // capability token is a renderer-time concern, not an install-time
        // failure.
        val manifest = PlugManifest(
            id = PlugId("exotic-plug"),
            name = "Exotic Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.DeviceCapability("some_future_capability"),
            ),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `duplicate device capability declarations are rejected`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.DeviceCapability("calendar"),
                PlugPermission.DeviceCapability("calendar"),
            ),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        assertEquals(
            listOf("calendar"),
            invalid.reasons
                .filterIsInstance<ManifestValidationReason.DuplicateDeviceCapability>()
                .map { it.capability },
        )
    }

    @Test
    fun `a link requirement scoped to a canon type the manifest neither emits nor consumes is rejected`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("calendar", setOf(CanonType.CALENDAR_EVENT))),
            emits = setOf(CanonType.PERSON),
            consumes = emptySet(),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        val undeclared = invalid.reasons
            .filterIsInstance<ManifestValidationReason.UndeclaredCanonScope>()
        assertEquals(1, undeclared.size)
        assertEquals("calendar", undeclared.single().requirementName)
        assertEquals(CanonType.CALENDAR_EVENT, undeclared.single().canonType)
    }

    @Test
    fun `a link requirement scoped to a canon type in either emits or consumes validates`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(
                linkRequirement("calendar", setOf(CanonType.CALENDAR_EVENT)),
                linkRequirement("contacts", setOf(CanonType.PERSON)),
            ),
            emits = setOf(CanonType.CALENDAR_EVENT),
            consumes = setOf(CanonType.PERSON),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a manifest declaring an optional canon type it does not also require validates`() {
        val manifest = PlugManifest(
            id = PlugId("vision-ocr-plug"),
            name = "Vision OCR Plug",
            version = "1.0.0",
            optionalConsumes = setOf(CanonType.PHOTO),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a link requirement scoped to a canon type only in optionalConsumes validates`() {
        val manifest = PlugManifest(
            id = PlugId("vision-ocr-plug"),
            name = "Vision OCR Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("photos", setOf(CanonType.PHOTO))),
            optionalConsumes = setOf(CanonType.PHOTO),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a canon type declared in both consumes and optionalConsumes is rejected`() {
        val manifest = PlugManifest(
            id = PlugId("vision-ocr-plug"),
            name = "Vision OCR Plug",
            version = "1.0.0",
            consumes = setOf(CanonType.PHOTO),
            optionalConsumes = setOf(CanonType.PHOTO),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        assertEquals(
            listOf(CanonType.PHOTO),
            invalid.reasons
                .filterIsInstance<ManifestValidationReason.RedundantOptionalConsumes>()
                .map { it.canonType },
        )
    }

    @Test
    fun `a canon-external manifest with an empty-scope link requirement validates`() {
        val manifest = PlugManifest(
            id = PlugId("vision-ocr-plug"),
            name = "Vision OCR Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("camera", emptySet())),
            isCanonExternal = true,
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a canon-external manifest with a non-empty undeclared scope validates`() {
        val manifest = PlugManifest(
            id = PlugId("vision-ocr-plug"),
            name = "Vision OCR Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("camera", setOf(CanonType.CALENDAR_EVENT))),
            isCanonExternal = true,
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a canon-bearing manifest with an empty scope still fails when isCanonExternal is unset`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("calendar", emptySet())),
            emits = setOf(CanonType.CALENDAR_EVENT),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        assertEquals(
            listOf("calendar"),
            invalid.reasons
                .filterIsInstance<ManifestValidationReason.EmptyLinkRequirementScope>()
                .map { it.name },
        )
    }

    @Test
    fun `a canon-bearing manifest with an undeclared scope still fails when isCanonExternal is unset`() {
        val manifest = PlugManifest(
            id = PlugId("calendar-plug"),
            name = "Calendar Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("calendar", setOf(CanonType.CALENDAR_EVENT))),
            emits = setOf(CanonType.PERSON),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        assertEquals(
            listOf(CanonType.CALENDAR_EVENT),
            invalid.reasons
                .filterIsInstance<ManifestValidationReason.UndeclaredCanonScope>()
                .map { it.canonType },
        )
    }

    @Test
    fun `isCanonExternal set while still declaring emits or consumes is rejected`() {
        val manifest = PlugManifest(
            id = PlugId("mislabelled-plug"),
            name = "Mislabelled Plug",
            version = "1.0.0",
            requiredLinks = listOf(linkRequirement("calendar", setOf(CanonType.CALENDAR_EVENT))),
            emits = setOf(CanonType.CALENDAR_EVENT),
            isCanonExternal = true,
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        val contradiction = invalid.reasons
            .filterIsInstance<ManifestValidationReason.CanonExternalWithDeclaredCanon>()
        assertEquals(1, contradiction.size)
        assertEquals(setOf(CanonType.CALENDAR_EVENT), contradiction.single().canonTypes)
    }

    @Test
    fun `a manifest written before isCanonExternal existed still decodes as canon-bearing`() {
        val manifest = PlugManifest(id = PlugId("legacy"), name = "Legacy", version = "0.1.0")

        assertTrue(!manifest.isCanonExternal)
    }

    @Test
    fun `a manifest written before link requirements existed still decodes`() {
        // The defaults are what keep pre-AMPR-223 manifests valid.
        val manifest = PlugManifest(id = PlugId("legacy"), name = "Legacy", version = "0.1.0")

        assertTrue(manifest.requiredLinks.isEmpty())
        assertTrue(manifest.emits.isEmpty())
        assertTrue(manifest.consumes.isEmpty())
        assertTrue(manifest.optionalConsumes.isEmpty())
        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    // -----------------------------------------------------------------
    // Table write capabilities (AMPR-263)
    // -----------------------------------------------------------------

    @Test
    fun `a manifest written before table write capabilities existed still decodes`() {
        val manifest = PlugManifest(id = PlugId("legacy"), name = "Legacy", version = "0.1.0")

        assertTrue(manifest.tableWriteCapabilities.isEmpty())
        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `a manifest declaring table write capabilities alongside TABLE in its canon contract validates`() {
        val manifest = PlugManifest(
            id = PlugId("csv-plug"),
            name = "CSV Plug",
            version = "1.0.0",
            emits = setOf(CanonType.TABLE),
            tableWriteCapabilities = setOf(TableWriteCapability.APPEND_ROW),
        )

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }

    @Test
    fun `table write capabilities declared without TABLE in emits or consumes is rejected`() {
        val manifest = PlugManifest(
            id = PlugId("csv-plug"),
            name = "CSV Plug",
            version = "1.0.0",
            tableWriteCapabilities = setOf(TableWriteCapability.APPEND_ROW),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        val undeclared = invalid.reasons
            .filterIsInstance<ManifestValidationReason.UndeclaredTableWriteCapability>()
        assertEquals(1, undeclared.size)
        assertEquals(setOf(TableWriteCapability.APPEND_ROW), undeclared.single().capabilities)
    }

    @Test
    fun `table write capabilities declared on a canon-external manifest is rejected`() {
        val manifest = PlugManifest(
            id = PlugId("mislabelled-plug"),
            name = "Mislabelled Plug",
            version = "1.0.0",
            isCanonExternal = true,
            tableWriteCapabilities = setOf(TableWriteCapability.UPDATE_CELL),
        )

        val invalid = assertIs<ManifestValidationResult.Invalid>(
            PlugManifestValidator.validate(manifest),
        )

        val contradiction = invalid.reasons
            .filterIsInstance<ManifestValidationReason.CanonExternalWithTableWriteCapabilities>()
        assertEquals(1, contradiction.size)
        assertEquals(setOf(TableWriteCapability.UPDATE_CELL), contradiction.single().capabilities)
    }

    @Test
    fun `a manifest declaring no table write capabilities validates without naming TABLE`() {
        val manifest = PlugManifest(id = PlugId("plain-plug"), name = "Plain Plug", version = "1.0.0")

        assertEquals(ManifestValidationResult.Valid, PlugManifestValidator.validate(manifest))
    }
}
