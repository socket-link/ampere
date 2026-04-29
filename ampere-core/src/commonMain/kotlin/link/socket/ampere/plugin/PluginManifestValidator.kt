package link.socket.ampere.plugin

import link.socket.ampere.plugin.permission.PluginPermission

/**
 * Pure-function validator for [PluginManifest].
 *
 * Today this enforces that every MCP server declared in
 * [PluginManifest.mcpServers] is matched by a corresponding
 * [PluginPermission.MCPServer] entry in [PluginManifest.requiredPermissions],
 * and that any permissions a dependency claims it needs are also lifted to
 * the manifest's top-level grant scope.
 *
 * Returning a sealed result keeps the validator usable both for early failure
 * (during plugin install) and for surfacing diagnostics in tooling.
 */
object PluginManifestValidator {

    fun validate(manifest: PluginManifest): ManifestValidationResult {
        val reasons = mutableListOf<ManifestValidationReason>()

        val grantedMcpUris = manifest.requiredPermissions
            .filterIsInstance<PluginPermission.MCPServer>()
            .map { it.uri }
            .toSet()

        manifest.mcpServers.forEach { dependency ->
            if (dependency.uri !in grantedMcpUris) {
                reasons += ManifestValidationReason.MissingMcpServerPermission(
                    dependencyName = dependency.name,
                    uri = dependency.uri,
                )
            }

            dependency.requiredPermissions.forEach { permission ->
                if (permission !in manifest.requiredPermissions) {
                    reasons += ManifestValidationReason.DependencyPermissionNotLifted(
                        dependencyName = dependency.name,
                        permission = permission,
                    )
                }
            }
        }

        return if (reasons.isEmpty()) {
            ManifestValidationResult.Valid
        } else {
            ManifestValidationResult.Invalid(reasons)
        }
    }
}

sealed interface ManifestValidationResult {
    data object Valid : ManifestValidationResult

    data class Invalid(val reasons: List<ManifestValidationReason>) : ManifestValidationResult
}

sealed interface ManifestValidationReason {

    /**
     * A [McpServerDependency] was declared but no matching
     * [PluginPermission.MCPServer] grant was present.
     */
    data class MissingMcpServerPermission(
        val dependencyName: String,
        val uri: String,
    ) : ManifestValidationReason

    /**
     * A [McpServerDependency] declared a permission that wasn't lifted to the
     * manifest's top-level [PluginManifest.requiredPermissions].
     */
    data class DependencyPermissionNotLifted(
        val dependencyName: String,
        val permission: PluginPermission,
    ) : ManifestValidationReason
}
