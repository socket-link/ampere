package link.socket.ampere.bundle

import link.socket.ampere.AMPERE_RUNTIME_VERSION
import link.socket.ampere.compareAmpereVersions
import link.socket.ampere.plug.ManifestValidationReason
import link.socket.ampere.plug.ManifestValidationResult
import link.socket.ampere.plug.PlugManifestValidator
import link.socket.ampere.plug.describe
import link.socket.ampere.plug.permission.PlugPermission

/**
 * Outcome of [PlugBundleValidator.validate].
 *
 * On success, the validator surfaces the de-duplicated permission set so the
 * marketplace UI (W1.10) can present an authoritative consent dialog without
 * re-walking the manifest. On failure, every reason is reported at once so
 * callers can render a complete diagnostic instead of forcing a fix-and-retry
 * loop.
 */
sealed interface BundleValidation {

    data class Ok(val permissions: List<PlugPermission>) : BundleValidation

    /**
     * @property reasons every failure, rendered for display. Bundle-level
     *   reasons first, then one line per [manifestReasons] entry — so a caller
     *   that only renders text still shows the manifest failures.
     * @property manifestReasons the typed subset: canon and manifest failures a
     *   host may want to act on rather than print — e.g. offering an upgrade for
     *   [ManifestValidationReason.AmpereVersionTooOld]. Reported alongside the
     *   strings rather than replacing them, so pre-AMPR-365 callers are
     *   unaffected.
     */
    data class Failed(
        val reasons: List<String>,
        val manifestReasons: List<ManifestValidationReason> = emptyList(),
    ) : BundleValidation
}

/**
 * Semantic validator for a parsed [PlugBundle].
 *
 * Distinct from [PlugBundleParser]: the parser ensures the bundle is
 * *readable*; the validator ensures it is *importable*. Failures here are
 * recoverable by the plug author (fix the manifest) rather than the host
 * runtime.
 *
 * Runs [PlugManifestValidator] over the embedded manifest (AMPR-365). Before
 * that, a marketplace import was held to fewer rules than an in-repo install:
 * the manifest validator's only call site was [link.socket.ampere.plug.PlugContext.create],
 * so an undeclared Link scope or an ungranted MCP dependency was accepted at
 * import and only refused later, at the first attempt to run the plug.
 *
 * @param hostAmpereVersion the runtime version a bundle's
 *   [PlugBundle.minimumAmpereVersion] pin is checked against. Defaults to this
 *   build's [AMPERE_RUNTIME_VERSION]; injectable so tests — and a host
 *   validating on behalf of a different runtime, e.g. a marketplace backend
 *   reviewing for older clients — can name their own.
 */
class PlugBundleValidator(
    private val hostAmpereVersion: String = AMPERE_RUNTIME_VERSION,
) {

    fun validate(bundle: PlugBundle): BundleValidation {
        val reasons = mutableListOf<String>()
        val manifestReasons = mutableListOf<ManifestValidationReason>()

        if (bundle.bundleFormatVersion != CURRENT_BUNDLE_FORMAT_VERSION) {
            reasons += "Unsupported bundleFormatVersion ${bundle.bundleFormatVersion}; " +
                "this build understands $CURRENT_BUNDLE_FORMAT_VERSION."
        }

        // Defence in depth, the same way the bundleFormatVersion check above is:
        // PlugBundleParser already rejects a too-new pin before decode, so this
        // only fires for a PlugBundle the caller assembled itself.
        ampereVersionPinReason(bundle.minimumAmpereVersion, hostAmpereVersion)
            ?.let { manifestReasons += it }

        val manifest = bundle.manifest
        // manifest.id has no blank check here: PlugId's init already rejected
        // an invalid id during deserialization, surfaced by the parser as
        // BundleParseError.InvalidManifest before validate() ever runs.
        if (manifest.name.isBlank()) reasons += "manifest.name is blank."
        if (manifest.version.isBlank()) reasons += "manifest.version is blank."

        manifest.requiredPermissions.forEachIndexed { index, permission ->
            val permissionReason = validatePermission(permission, index)
            if (permissionReason != null) reasons += permissionReason
        }

        if (bundle.signature != null && bundle.signature.isEmpty()) {
            reasons += "signature.sig is present but empty."
        }

        val manifestValidation = PlugManifestValidator.validate(manifest)
        if (manifestValidation is ManifestValidationResult.Invalid) {
            manifestReasons += manifestValidation.reasons
        }

        reasons += manifestReasons.map { it.describe() }

        return if (reasons.isEmpty()) {
            BundleValidation.Ok(permissions = manifest.requiredPermissions.distinct())
        } else {
            BundleValidation.Failed(
                reasons = reasons.toList(),
                manifestReasons = manifestReasons.toList(),
            )
        }
    }

    private fun validatePermission(permission: PlugPermission, index: Int): String? {
        val (field, value) = when (permission) {
            is PlugPermission.NetworkDomain -> "host" to permission.host
            is PlugPermission.MCPServer -> "uri" to permission.uri
            is PlugPermission.KnowledgeQuery -> "scope" to permission.scope
            is PlugPermission.NativeAction -> "actionId" to permission.actionId
            is PlugPermission.LinkAccess -> "linkId" to permission.linkId
            is PlugPermission.DeviceCapability -> "capability" to permission.capability
        }
        return if (value.isBlank()) {
            "requiredPermissions[$index] (${permission::class.simpleName}) has blank $field."
        } else {
            null
        }
    }
}

/**
 * Checks a bundle's declared canon pin against a host runtime version, returning
 * the reason it fails or `null` when the host is new enough.
 *
 * Shared by [PlugBundleParser] (pre-decode, where the pin is still a raw JSON
 * string) and [PlugBundleValidator] (post-decode), so the two cannot drift on
 * what "too old" means.
 *
 * A [ManifestValidationReason.MalformedAmpereVersion] here always indicts the
 * bundle, never the host: [AMPERE_RUNTIME_VERSION] is held to
 * `gradle.properties` by the `verifyAmpereVersionConstant` build task, so a host
 * version that cannot be ordered fails the build long before it ships.
 */
internal fun ampereVersionPinReason(
    declared: String?,
    hostAmpereVersion: String,
): ManifestValidationReason? {
    if (declared == null) return null

    val comparison = compareAmpereVersions(declared, hostAmpereVersion)
        ?: return ManifestValidationReason.MalformedAmpereVersion(declared)

    return if (comparison > 0) {
        ManifestValidationReason.AmpereVersionTooOld(
            required = declared,
            current = hostAmpereVersion,
        )
    } else {
        null
    }
}
