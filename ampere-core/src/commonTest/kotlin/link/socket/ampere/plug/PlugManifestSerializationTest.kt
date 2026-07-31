package link.socket.ampere.plug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import link.socket.ampere.plug.permission.PlugPermission

class PlugManifestSerializationTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `round-trips a manifest through json unchanged`() {
        val manifest = PlugManifest(
            id = PlugId("github-plug"),
            name = "GitHub Plug",
            version = "1.0.0",
            description = "Open and review GitHub PRs from inside Ampere.",
            requiredPermissions = listOf(
                PlugPermission.NetworkDomain("api.github.com"),
                PlugPermission.MCPServer("mcp://github"),
            ),
            mcpServers = listOf(McpServerDependency(name = "github", uri = "mcp://github")),
        )

        val encoded = json.encodeToString(PlugManifest.serializer(), manifest)
        val decoded = json.decodeFromString(PlugManifest.serializer(), encoded)

        assertEquals(manifest, decoded)
    }

    @Test
    fun `a pre-existing manifest still decodes with its now-removed entrypoint field ignored`() {
        val legacyManifestJson = """
            {
              "id": "github-plug",
              "name": "GitHub Plug",
              "version": "1.0.0",
              "entrypoint": "main",
              "requiredPermissions": [
                { "type": "mcp_server", "uri": "mcp://github" }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString(PlugManifest.serializer(), legacyManifestJson)

        assertEquals(PlugId("github-plug"), decoded.id)
        assertEquals("GitHub Plug", decoded.name)
        assertEquals(listOf(PlugPermission.MCPServer("mcp://github")), decoded.requiredPermissions)
    }
}
