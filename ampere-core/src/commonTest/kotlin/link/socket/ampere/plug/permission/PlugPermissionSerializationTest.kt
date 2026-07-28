package link.socket.ampere.plug.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.plug.PlugManifest

class PlugPermissionSerializationTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    @Test
    fun `round-trips all permission variants through json`() {
        val permissions = listOf(
            PlugPermission.NetworkDomain("api.example.com"),
            PlugPermission.MCPServer("mcp://github"),
            PlugPermission.KnowledgeQuery("workspace"),
            PlugPermission.NativeAction("open-url"),
            PlugPermission.LinkAccess("linear-AMPR-149"),
        )

        permissions.forEach { permission ->
            val encoded = json.encodeToString(PlugPermission.serializer(), permission)
            val decoded = json.decodeFromString(PlugPermission.serializer(), encoded)

            assertEquals(permission, decoded)
        }
    }

    @Test
    fun `manifest missing required permissions defaults to empty list`() {
        val manifest = json.decodeFromString<PlugManifest>(
            """
            {
              "id": "test-plug",
              "name": "Test Plug",
              "version": "1.0.0"
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), manifest.requiredPermissions)
    }

    @Test
    fun `manifest surfaces declared required permissions`() {
        val original = PlugManifest(
            id = "github-plug",
            name = "GitHub Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.NetworkDomain("api.github.com"),
                PlugPermission.MCPServer("mcp://github"),
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PlugManifest>(encoded)

        assertEquals(original.requiredPermissions, decoded.requiredPermissions)
    }

    @Test
    fun `manifest missing mcp servers defaults to empty list`() {
        val manifest = json.decodeFromString<PlugManifest>(
            """
            {
              "id": "test-plug",
              "name": "Test Plug",
              "version": "1.0.0"
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), manifest.mcpServers)
    }

    @Test
    fun `manifest round-trips mcp server dependencies through json`() {
        val original = PlugManifest(
            id = "github-plug",
            name = "GitHub Plug",
            version = "1.0.0",
            requiredPermissions = listOf(
                PlugPermission.MCPServer("mcp://github"),
                PlugPermission.MCPServer("mcp://notion"),
            ),
            mcpServers = listOf(
                McpServerDependency(
                    name = "github",
                    uri = "mcp://github",
                    requiredPermissions = listOf(
                        PlugPermission.NetworkDomain("api.github.com"),
                    ),
                ),
                McpServerDependency(
                    name = "notion",
                    uri = "mcp://notion",
                ),
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PlugManifest>(encoded)

        assertEquals(original.mcpServers, decoded.mcpServers)
        assertEquals(original.requiredPermissions, decoded.requiredPermissions)
    }
}
