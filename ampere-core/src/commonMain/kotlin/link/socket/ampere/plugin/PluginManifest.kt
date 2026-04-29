package link.socket.ampere.plugin

import kotlinx.serialization.Serializable
import link.socket.ampere.plugin.permission.PluginPermission

/**
 * Manifest metadata for a plugin and the permissions it requires at runtime.
 *
 * [requiredPermissions] and [mcpServers] default to empty so manifests created
 * before each schema addition continue to decode unchanged.
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val entrypoint: String? = null,
    val requiredPermissions: List<PluginPermission> = emptyList(),
    val mcpServers: List<McpServerDependency> = emptyList(),
)
