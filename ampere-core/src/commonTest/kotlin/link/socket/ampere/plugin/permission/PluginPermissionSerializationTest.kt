package link.socket.ampere.plugin.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import link.socket.ampere.plugin.PluginManifest

class PluginPermissionSerializationTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    @Test
    fun `round-trips all permission variants through json`() {
        val permissions = listOf(
            PluginPermission.NetworkDomain("api.example.com"),
            PluginPermission.MCPServer("mcp://github"),
            PluginPermission.KnowledgeQuery("workspace"),
            PluginPermission.NativeAction("open-url"),
            PluginPermission.LinkAccess("linear-AMPR-149"),
        )

        permissions.forEach { permission ->
            val encoded = json.encodeToString(PluginPermission.serializer(), permission)
            val decoded = json.decodeFromString(PluginPermission.serializer(), encoded)

            assertEquals(permission, decoded)
        }
    }

    @Test
    fun `manifest missing required permissions defaults to empty list`() {
        val manifest = json.decodeFromString<PluginManifest>(
            """
            {
              "id": "test-plugin",
              "name": "Test Plugin",
              "version": "1.0.0"
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), manifest.requiredPermissions)
    }

    @Test
    fun `manifest surfaces declared required permissions`() {
        val original = PluginManifest(
            id = "github-plugin",
            name = "GitHub Plugin",
            version = "1.0.0",
            requiredPermissions = listOf(
                PluginPermission.NetworkDomain("api.github.com"),
                PluginPermission.MCPServer("mcp://github"),
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PluginManifest>(encoded)

        assertEquals(original.requiredPermissions, decoded.requiredPermissions)
    }
}
