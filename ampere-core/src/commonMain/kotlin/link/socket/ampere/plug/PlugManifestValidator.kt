package link.socket.ampere.plug

import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.table.TableWriteCapability
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
 * [PlugManifest.isCanonExternal] for internal consistency. Whether a canon
 * *name* exists at all is settled before a [PlugManifest] can be built, by
 * enum decode and — for bundles — by
 * [link.socket.ampere.bundle.PlugBundleParser]'s pre-decode pass, which is why
 * [ManifestValidationReason.UnknownCanonType] is in the reason set but never
 * produced here.
 *
 * Production call sites are [PlugContext.create] and
 * [link.socket.ampere.bundle.PlugBundleValidator] (AMPR-365), so a marketplace
 * import is held to the same rules as an in-repo install. It is expected to run
 * at plug install time on every host that accepts manifests — including Socket,
 * which does not currently call it.
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
        reasons += validateTableWriteCapabilities(manifest)

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
     * [PlugManifest.isCanonExternal] exempts [PlugManifest.emits] from the
     * canon contract and nothing else (AMPR-320): a canon-external Plug may
     * still declare [PlugManifest.consumes] and
     * [PlugManifest.optionalConsumes], and those are what its
     * [LinkRequirement.minimumScope]s are checked against. Only a
     * canon-external Plug that also consumes no canon at all is left with no
     * type it could truthfully name, and only then are the empty-scope and
     * undeclared-canon-scope rules skipped wholesale. This is a carve-out for
     * a positively-declared state, not an inference from empty
     * [PlugManifest.emits]/[PlugManifest.consumes] — a canon-bearing Plug that
     * leaves those collections empty by mistake still fails both rules
     * unchanged. [CanonExternalWithDeclaredCanon] catches the inverse mistake:
     * [PlugManifest.isCanonExternal] set while still claiming to *emit* canon.
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

        if (manifest.isCanonExternal && manifest.emits.isNotEmpty()) {
            reasons += ManifestValidationReason.CanonExternalWithDeclaredCanon(
                canonTypes = manifest.emits,
            )
        }

        val declaredCanonTypes = manifest.consumes +
            manifest.optionalConsumes +
            if (manifest.isCanonExternal) emptySet() else manifest.emits

        // The AMPR-260 carve-out survives only for a canon-external Plug that
        // consumes nothing either: it has no canon type it could truthfully
        // name in a scope, so requiring one would be unsatisfiable.
        val exemptFromScopeRules = manifest.isCanonExternal && declaredCanonTypes.isEmpty()

        if (!exemptFromScopeRules) {
            manifest.requiredLinks
                .filter { it.minimumScope.isEmpty() }
                .forEach { requirement ->
                    reasons += ManifestValidationReason.EmptyLinkRequirementScope(requirement.name)
                }

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

    /**
     * A [PlugManifest.tableWriteCapabilities] declaration only makes sense
     * for a Plug that actually has `TABLE` in its canon-level data contract
     * — same asymmetry [validateLinkRequirements] enforces for
     * [link.socket.ampere.link.LinkRequirement.minimumScope], applied to
     * write capabilities instead of read scope.
     */
    private fun validateTableWriteCapabilities(
        manifest: PlugManifest,
    ): List<ManifestValidationReason> {
        if (manifest.tableWriteCapabilities.isEmpty()) return emptyList()

        val reasons = mutableListOf<ManifestValidationReason>()

        if (manifest.isCanonExternal) {
            reasons += ManifestValidationReason.CanonExternalWithTableWriteCapabilities(
                capabilities = manifest.tableWriteCapabilities,
            )
        } else if (CanonType.TABLE !in manifest.emits + manifest.consumes) {
            reasons += ManifestValidationReason.UndeclaredTableWriteCapability(
                capabilities = manifest.tableWriteCapabilities,
            )
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
     * [PlugManifest.isCanonExternal] declares that a Plug's observations are
     * outside canon, but [PlugManifest.emits] is non-empty — a contradiction
     * that would otherwise silently exempt a canon-emitting Plug from
     * [EmptyLinkRequirementScope] and [UndeclaredCanonScope].
     *
     * [PlugManifest.consumes] and [PlugManifest.optionalConsumes] are *not*
     * a contradiction with the flag (AMPR-320) — a Plug whose outputs are
     * external can still take canon in — so they never appear here.
     */
    data class CanonExternalWithDeclaredCanon(
        val canonTypes: Set<CanonType>,
    ) : ManifestValidationReason

    /**
     * [PlugManifest.tableWriteCapabilities] is non-empty but the manifest
     * names neither [CanonType.TABLE] in [PlugManifest.emits] nor
     * [PlugManifest.consumes] — a Plug asking to write a canon type it never
     * declared handling.
     */
    data class UndeclaredTableWriteCapability(
        val capabilities: Set<TableWriteCapability>,
    ) : ManifestValidationReason

    /**
     * [PlugManifest.isCanonExternal] declares no canon-level data contract,
     * but [PlugManifest.tableWriteCapabilities] is non-empty — the same
     * contradiction [CanonExternalWithDeclaredCanon] catches for
     * [PlugManifest.emits]/[PlugManifest.consumes], for write capabilities.
     */
    data class CanonExternalWithTableWriteCapabilities(
        val capabilities: Set<TableWriteCapability>,
    ) : ManifestValidationReason

    /**
     * A canon-facing manifest field named a wire name this build's [CanonType]
     * does not contain: an extension type (`com.acme.invoice`), a typo, or a
     * noun admitted to the canon after this build shipped.
     *
     * Produced by [link.socket.ampere.bundle.PlugBundleParser], not by
     * [PlugManifestValidator] — [PlugManifest.emits] and its siblings are
     * `Set<CanonType>`, so by the time a [PlugManifest] exists an unknown name
     * has already failed enum decode. The parser reads the canon fields
     * leniently off the raw JSON *before* decoding, which is what lets a host
     * name the offending field and value instead of surfacing a decoder message
     * as [link.socket.ampere.bundle.BundleParseError.InvalidManifest]
     * (AMPR-365).
     *
     * @property field the manifest field that named it, relative to `plug`:
     *   `emits`, `consumes`, `optionalConsumes`, or
     *   `requiredLinks[0].minimumScope`.
     * @property wireName the unresolvable name, verbatim.
     */
    data class UnknownCanonType(
        val field: String,
        val wireName: String,
    ) : ManifestValidationReason

    /**
     * A bundle pinned a minimum Ampere version newer than the host's
     * [link.socket.ampere.AMPERE_RUNTIME_VERSION]: the host is too old to be
     * sure it has every canon type the bundle names.
     *
     * This is the honest version of the SCKT-444 failure, where a host pinned
     * to an older `ampere-core` met a bundle naming a canon type admitted after
     * it — and reported a malformed manifest. Checked before decode by
     * [link.socket.ampere.bundle.PlugBundleParser] so the "upgrade required"
     * diagnostic wins over the unknown-noun one it would otherwise cause, and
     * again by [link.socket.ampere.bundle.PlugBundleValidator] for a bundle that
     * was handed over rather than parsed.
     *
     * @property required the version the bundle pinned.
     * @property current the host runtime version it was compared against.
     */
    data class AmpereVersionTooOld(
        val required: String,
        val current: String,
    ) : ManifestValidationReason

    /**
     * A bundle declared a [link.socket.ampere.bundle.BundleManifest.minimumAmpereVersion]
     * that is not an orderable version (`"latest"`, `"1.x"`, `""`).
     *
     * Reported rather than ignored: a pin that cannot be compared is a gate
     * that cannot be enforced, and silently importing such a bundle is exactly
     * the skew the pin exists to catch.
     */
    data class MalformedAmpereVersion(
        val declared: String,
    ) : ManifestValidationReason
}

/**
 * One-line rendering of a reason, for hosts that surface validation failures as
 * text — [link.socket.ampere.bundle.BundleValidation.Failed.reasons] is the
 * in-tree caller.
 *
 * Kept next to the sealed set so a new reason cannot be added without the
 * exhaustive `when` here failing to compile.
 */
fun ManifestValidationReason.describe(): String = when (this) {
    is ManifestValidationReason.MissingMcpServerPermission ->
        "mcpServers[$dependencyName] declares $uri but no matching mcp_server permission was granted."

    is ManifestValidationReason.DependencyPermissionNotLifted ->
        "mcpServers[$dependencyName] requires $permission, which is not in requiredPermissions."

    is ManifestValidationReason.DuplicateLinkRequirementName ->
        "requiredLinks declares the name \"$name\" more than once; only one could ever be resolved."

    is ManifestValidationReason.EmptyLinkRequirementScope ->
        "requiredLinks[$name] declares an empty minimumScope, so it would resolve to a wire " +
            "permitted to carry nothing."

    is ManifestValidationReason.DuplicateDeviceCapability ->
        "requiredPermissions declares the device capability \"$capability\" more than once."

    is ManifestValidationReason.UndeclaredCanonScope ->
        "requiredLinks[$requirementName] is scoped to ${canonType.wireName}, which the manifest " +
            "neither emits, consumes, nor optionally consumes."

    is ManifestValidationReason.RedundantOptionalConsumes ->
        "${canonType.wireName} is in both consumes and optionalConsumes; it cannot be both " +
            "required and merely accepted."

    is ManifestValidationReason.CanonExternalWithDeclaredCanon ->
        "isCanonExternal is set but emits declares " +
            "${canonTypes.joinToString { it.wireName }}."

    is ManifestValidationReason.UndeclaredTableWriteCapability ->
        "tableWriteCapabilities declares ${capabilities.joinToString()} but neither emits nor " +
            "consumes names ${CanonType.TABLE.wireName}."

    is ManifestValidationReason.CanonExternalWithTableWriteCapabilities ->
        "isCanonExternal is set but tableWriteCapabilities declares " +
            "${capabilities.joinToString()}."

    is ManifestValidationReason.UnknownCanonType ->
        "$field names \"$wireName\", which is not a canon type this build understands."

    is ManifestValidationReason.AmpereVersionTooOld ->
        "minimumAmpereVersion is $required but this build is $current; upgrade Ampere to import " +
            "this bundle."

    is ManifestValidationReason.MalformedAmpereVersion ->
        "minimumAmpereVersion \"$declared\" is not a version that can be compared."
}
