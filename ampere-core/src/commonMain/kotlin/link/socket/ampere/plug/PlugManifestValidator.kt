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

        reasons += validateLinkRequirements(manifest)

        return if (reasons.isEmpty()) {
            ManifestValidationResult.Valid
        } else {
            ManifestValidationResult.Invalid(reasons)
        }
    }

    /**
     * Structural checks on [PlugManifest.requiredLinks].
     *
     * Both rules exist because the failure they catch is silent otherwise: a
     * duplicate requirement name means one of the two Links is unreachable
     * through [link.socket.ampere.link.ResolvedLinks], and an empty scope means
     * a wire that resolves successfully and then may carry nothing.
     */
    private fun validateLinkRequirements(
        manifest: PlugManifest,
    ): List<ManifestValidationReason> {
        val reasons = mutableListOf<ManifestValidationReason>()

        manifest.requiredLinks
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .forEach { name ->
                reasons += ManifestValidationReason.DuplicateLinkRequirementName(name)
            }

        manifest.requiredLinks
            .filter { it.minimumScope.isEmpty() }
            .forEach { requirement ->
                reasons += ManifestValidationReason.EmptyLinkRequirementScope(requirement.name)
            }

        return reasons
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

    /**
     * Two [link.socket.ampere.link.LinkRequirement]s share a name, so only one
     * of them can ever be looked up after resolution.
     */
    data class DuplicateLinkRequirementName(
        val name: String,
    ) : ManifestValidationReason

    /**
     * A Link requirement declares no minimum scope, which would resolve to a
     * wire permitted to carry nothing.
     */
    data class EmptyLinkRequirementScope(
        val name: String,
    ) : ManifestValidationReason
}
