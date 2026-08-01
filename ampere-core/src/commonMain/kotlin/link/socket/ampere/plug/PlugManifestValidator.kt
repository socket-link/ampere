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
 * against anything outside the manifest itself — it only cross-references
 * them against [PlugManifest.requiredLinks] scopes and
 * [PlugManifest.optionalConsumes], and checks them against
 * [PlugManifest.isCanonExternal] for internal consistency. The only
 * production call site today is [PlugContext.create]. It is expected to run
 * at plug install time on every host that accepts manifests — including
 * Socket, which does not currently call it.
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
        reasons += validateCanonConsumption(manifest)

        return if (reasons.isEmpty()) {
            ManifestValidationResult.Valid
        } else {
            ManifestValidationResult.Invalid(reasons)
        }
    }

    /**
     * Structural checks on [PlugManifest.requiredLinks].
     *
     * All four rules exist because the failure they catch is silent
     * otherwise: a duplicate requirement name means one of the two Links is
     * unreachable through [link.socket.ampere.link.ResolvedLinks], an empty
     * scope means a wire that resolves successfully and then may carry
     * nothing, and a scope naming a canon type the Plug never declares means
     * the Plug is asking for data it has no stated way to produce or use.
     *
     * The empty-scope and undeclared-canon-scope rules are skipped entirely
     * for a [PlugManifest.isCanonExternal] Plug: by declaration it has no
     * canon type it could truthfully name, so every [LinkRequirement] is
     * allowed an empty [LinkRequirement.minimumScope] and any non-empty
     * scope is exempt from the "declared in emits/consumes/optionalConsumes"
     * check. This is a carve-out for a positively-declared state, not an
     * inference from empty [PlugManifest.emits]/[PlugManifest.consumes] — a
     * canon-bearing Plug that leaves those collections empty by mistake
     * still fails both rules unchanged. [CanonExternalWithDeclaredCanon]
     * catches the inverse mistake: [PlugManifest.isCanonExternal] set while
     * still claiming a canon type.
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

        if (manifest.isCanonExternal && (manifest.emits.isNotEmpty() || manifest.consumes.isNotEmpty())) {
            reasons += ManifestValidationReason.CanonExternalWithDeclaredCanon(
                canonTypes = manifest.emits + manifest.consumes,
            )
        }

        if (!manifest.isCanonExternal) {
            manifest.requiredLinks
                .filter { it.minimumScope.isEmpty() }
                .forEach { requirement ->
                    reasons += ManifestValidationReason.EmptyLinkRequirementScope(requirement.name)
                }

            val declaredCanonTypes = manifest.emits + manifest.consumes + manifest.optionalConsumes
            manifest.requiredLinks.forEach { requirement ->
                (requirement.minimumScope - declaredCanonTypes).forEach { undeclared ->
                    reasons += ManifestValidationReason.UndeclaredCanonScope(
                        requirementName = requirement.name,
                        canonType = undeclared,
                    )
                }
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

    /**
     * A canon type in both [PlugManifest.consumes] and
     * [PlugManifest.optionalConsumes] is a contradiction: the Plug cannot
     * simultaneously require and merely-accept-if-available the same type.
     */
    private fun validateCanonConsumption(
        manifest: PlugManifest,
    ): List<ManifestValidationReason> {
        return (manifest.consumes intersect manifest.optionalConsumes)
            .map { ManifestValidationReason.RedundantOptionalConsumes(it) }
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
     * [CanonType] the manifest declares in none of [PlugManifest.emits],
     * [PlugManifest.consumes], or [PlugManifest.optionalConsumes] — the Plug
     * is asking for data it has no stated way to produce or use.
     */
    data class UndeclaredCanonScope(
        val requirementName: String,
        val canonType: CanonType,
    ) : ManifestValidationReason

    /**
     * A [CanonType] appears in both [PlugManifest.consumes] and
     * [PlugManifest.optionalConsumes] — the Plug cannot both require and
     * merely-accept-if-available the same canon type.
     */
    data class RedundantOptionalConsumes(
        val canonType: CanonType,
    ) : ManifestValidationReason

    /**
     * [PlugManifest.isCanonExternal] declares that a Plug has no canon-level
     * data contract, but [PlugManifest.emits] or [PlugManifest.consumes] is
     * non-empty — a contradiction that would otherwise silently exempt a
     * canon-bearing Plug from [EmptyLinkRequirementScope] and
     * [UndeclaredCanonScope].
     */
    data class CanonExternalWithDeclaredCanon(
        val canonTypes: Set<CanonType>,
    ) : ManifestValidationReason
}
