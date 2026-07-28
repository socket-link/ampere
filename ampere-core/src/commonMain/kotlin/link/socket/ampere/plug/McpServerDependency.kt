package link.socket.ampere.plug

import kotlinx.serialization.Serializable
import link.socket.ampere.plug.permission.PlugPermission

/**
 * Declares an MCP server that a plug depends on at runtime.
 *
 * Each declared dependency must be matched by a corresponding
 * [PlugPermission.MCPServer] entry in [PlugManifest.requiredPermissions]
 * so the user grant flow can authorize the plug's access to it.
 *
 * @property name Local handle used by the plug to reference the server
 *   (e.g., `"notion"`). Distinct from the connection [uri] so plugs can
 *   reference servers symbolically.
 * @property uri The MCP server endpoint (e.g., `"mcp://..."` or
 *   `"https://..."`). Used both to dial the server and to match the
 *   [PlugPermission.MCPServer] grant.
 * @property requiredPermissions Permissions the plug will exercise via
 *   this server. Each must also appear in
 *   [PlugManifest.requiredPermissions]; otherwise the manifest validator
 *   surfaces a diagnostic so the plug author can lift the permission to
 *   the top-level grant scope.
 */
@Serializable
data class McpServerDependency(
    val name: String,
    val uri: String,
    val requiredPermissions: List<PlugPermission> = emptyList(),
)
