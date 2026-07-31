package link.socket.ampere.plug

import link.socket.ampere.canon.CanonType
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
 *
 * This validator does not check [PlugManifest.emits] / [PlugManifest.consumes]
 * at all, and the only production call site today is [PlugContext.create].
 * It is expected to run at plug install time on every host that accepts
 * manifests — including Socket, which does not currently call it.
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
        reasons += validateDeviceCapabilities(manifest)

        return if (reasons.isEmpty()) {
            ManifestValidationResult.Valid
        } else {
            ManifestValidationResult.Invalid(reasons)
        }
    }

    /**
     * Structural checks on [PlugManifest.requiredLinks].
     *
     * All three rules exist because the failure they catch is silent
     * otherwise: a duplicate requirement name means one of the two Links is
     * unreachable through [link.socket.ampere.link.ResolvedLinks], an empty
     * scope means a wire that resolves successfully and then may carry
     * nothing, and a scope naming a canon type the Plug never declares means
     * the Plug is asking for data it has no stated way to produce or use.
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

        val declaredCanonTypes = manifest.emits + manifest.consumes
        manifest.requiredLinks.forEach { requirement ->
            (requirement.minimumScope - declaredCanonTypes).forEach { undeclared ->
                reasons += ManifestValidationReason.UndeclaredCanonScope(
                    requirementName = requirement.name,
                    canonType = undeclared,
                )
            }
        }

        return reasons
    }

    /**
     * A manifest declaring the same device capability token more than once
     * is almost always a copy-paste mistake, not two distinct grants — the
     * OS authorization APIs this maps to have no notion of "granted twice".
     */
    private fun validateDeviceCapabilities(
        manifest: PlugManifest,
    ): List<ManifestValidationReason> {
        return manifest.requiredPermissions
            .filterIsInstance<PlugPermission.DeviceCapability>()
            .groupBy { it.capability }
            .filterValues { it.size > 1 }
            .keys
            .map { ManifestValidationReason.DuplicateDeviceCapability(it) }
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

    /**
     * The same [PlugPermission.DeviceCapability] token was declared more
     * than once in [PlugManifest.requiredPermissions].
     */
    data class DuplicateDeviceCapability(
        val capability: String,
    ) : ManifestValidationReason

    /**
     * A [link.socket.ampere.link.LinkRequirement.minimumScope] names a
     * [CanonType] the manifest neither [PlugManifest.emits] nor
     * [PlugManifest.consumes] — the Plug is asking for data it has no stated
     * way to produce or use.
     */
    data class UndeclaredCanonScope(
        val requirementName: String,
        val canonType: CanonType,
    ) : ManifestValidationReason
}
