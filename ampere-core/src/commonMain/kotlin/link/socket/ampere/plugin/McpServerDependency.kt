package link.socket.ampere.plugin

import kotlinx.serialization.Serializable
import link.socket.ampere.plugin.permission.PluginPermission

/**
 * Declares an MCP server that a plugin depends on at runtime.
 *
 * Each declared dependency must be matched by a corresponding
 * [PluginPermission.MCPServer] entry in [PluginManifest.requiredPermissions]
 * so the user grant flow can authorize the plugin's access to it.
 *
 * @property name Local handle used by the plugin to reference the server
 *   (e.g., `"notion"`). Distinct from the connection [uri] so plugins can
 *   reference servers symbolically.
 * @property uri The MCP server endpoint (e.g., `"mcp://..."` or
 *   `"https://..."`). Used both to dial the server and to match the
 *   [PluginPermission.MCPServer] grant.
 * @property requiredPermissions Permissions the plugin will exercise via
 *   this server. Each must also appear in
 *   [PluginManifest.requiredPermissions]; otherwise the manifest validator
 *   surfaces a diagnostic so the plugin author can lift the permission to
 *   the top-level grant scope.
 */
@Serializable
data class McpServerDependency(
    val name: String,
    val uri: String,
    val requiredPermissions: List<PluginPermission> = emptyList(),
)
