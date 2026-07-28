package link.socket.ampere.plug

import link.socket.ampere.plug.permission.PlugPermission

/**
 * Pure-function validator for [PlugManifest].
 *
 * Today this enforces that every MCP server declared in
 * [PlugManifest.mcpServers] is matched by a corresponding
 * [PlugPermission.MCPServer] entry in [PlugManifest.requiredPermissions],
 * and that any permissions a dependency claims it needs are also lifted to
 * the manifest's top-level grant scope.
 *
 * Returning a sealed result keeps the validator usable both for early failure
 * (during plug install) and for surfacing diagnostics in tooling.
 */
object PlugManifestValidator {

    fun validate(manifest: PlugManifest): ManifestValidationResult {
        val reasons = mutableListOf<ManifestValidationReason>()

        val grantedMcpUris = manifest.requiredPermissions
            .filterIsInstance<PlugPermission.MCPServer>()
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
     * [PlugPermission.MCPServer] grant was present.
     */
    data class MissingMcpServerPermission(
        val dependencyName: String,
        val uri: String,
    ) : ManifestValidationReason

    /**
     * A [McpServerDependency] declared a permission that wasn't lifted to the
     * manifest's top-level [PlugManifest.requiredPermissions].
     */
    data class DependencyPermissionNotLifted(
        val dependencyName: String,
        val permission: PlugPermission,
    ) : ManifestValidationReason
}
