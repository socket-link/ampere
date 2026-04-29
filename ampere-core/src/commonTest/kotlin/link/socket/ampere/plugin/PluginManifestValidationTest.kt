package link.socket.ampere.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.ampere.plugin.permission.PluginPermission

class PluginManifestValidationTest {

    @Test
    fun `manifest with declared mcp server and matching permission validates`() {
        val manifest = PluginManifest(
            id = "github-plugin",
            name = "GitHub Plugin",
            version = "1.0.0",
            requiredPermissions = listOf(
                PluginPermission.MCPServer("mcp://github"),
            ),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                ),
            ),
        )

        val result = PluginManifestValidator.validate(manifest)

        assertEquals(ManifestValidationResult.Valid, result)
    }

    @Test
    fun `manifest missing matching mcp permission fails with diagnostic naming the uri`() {
        val manifest = PluginManifest(
            id = "github-plugin",
            name = "GitHub Plugin",
            version = "1.0.0",
            requiredPermissions = emptyList(),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                ),
            ),
        )

        val result = PluginManifestValidator.validate(manifest)
        val invalid = assertIs<ManifestValidationResult.Invalid>(result)

        val missing = invalid.reasons.filterIsInstance<ManifestValidationReason.MissingMcpServerPermission>()
        assertEquals(1, missing.size)
        assertEquals("mcp://github", missing.single().uri)
        assertEquals("github", missing.single().dependencyName)
    }

    @Test
    fun `dependency permission not lifted to manifest is flagged`() {
        val knowledgeQuery = PluginPermission.KnowledgeQuery("workspace")
        val manifest = PluginManifest(
            id = "github-plugin",
            name = "GitHub Plugin",
            version = "1.0.0",
            requiredPermissions = listOf(
                PluginPermission.MCPServer("mcp://github"),
            ),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                    requiredPermissions = listOf(knowledgeQuery),
                ),
            ),
        )

        val result = PluginManifestValidator.validate(manifest)
        val invalid = assertIs<ManifestValidationResult.Invalid>(result)

        val notLifted = invalid.reasons
            .filterIsInstance<ManifestValidationReason.DependencyPermissionNotLifted>()
        assertEquals(1, notLifted.size)
        assertEquals(knowledgeQuery, notLifted.single().permission)
        assertEquals("github", notLifted.single().dependencyName)
    }

    @Test
    fun `manifest with no mcp servers validates regardless of permissions`() {
        val manifest = PluginManifest(
            id = "no-mcp-plugin",
            name = "No MCP Plugin",
            version = "1.0.0",
        )

        val result = PluginManifestValidator.validate(manifest)

        assertEquals(ManifestValidationResult.Valid, result)
    }

    @Test
    fun `multiple mcp servers all require their own permissions`() {
        val manifest = PluginManifest(
            id = "multi-mcp",
            name = "Multi MCP",
            version = "1.0.0",
            requiredPermissions = listOf(
                PluginPermission.MCPServer("mcp://a"),
            ),
            mcpServers = listOf(
                McpServerDependency(name = "a", uri = "mcp://a"),
                McpServerDependency(name = "b", uri = "mcp://b"),
            ),
        )

        val result = PluginManifestValidator.validate(manifest)
        val invalid = assertIs<ManifestValidationResult.Invalid>(result)

        val missingUris = invalid.reasons
            .filterIsInstance<ManifestValidationReason.MissingMcpServerPermission>()
            .map { it.uri }
        assertTrue("mcp://b" in missingUris)
        assertTrue("mcp://a" !in missingUris)
    }
}
