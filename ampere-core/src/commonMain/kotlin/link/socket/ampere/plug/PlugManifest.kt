package link.socket.ampere.plug

import kotlinx.serialization.Serializable
import link.socket.ampere.plug.permission.PlugPermission

/**
 * Manifest metadata for a plug and the permissions it requires at runtime.
 *
 * [requiredPermissions] and [mcpServers] default to empty so manifests created
 * before each schema addition continue to decode unchanged.
 */
@Serializable
data class PlugManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val entrypoint: String? = null,
    val requiredPermissions: List<PlugPermission> = emptyList(),
    val mcpServers: List<McpServerDependency> = emptyList(),
)
