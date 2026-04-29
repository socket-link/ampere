package link.socket.ampere.bundle

import link.socket.ampere.plugin.permission.PluginPermission

/**
 * Outcome of [PluginBundleValidator.validate].
 *
 * On success, the validator surfaces the de-duplicated permission set so the
 * marketplace UI (W1.10) can present an authoritative consent dialog without
 * re-walking the manifest. On failure, every reason is reported at once so
 * callers can render a complete diagnostic instead of forcing a fix-and-retry
 * loop.
 */
sealed interface BundleValidation {

    data class Ok(val permissions: List<PluginPermission>) : BundleValidation

    data class Failed(val reasons: List<String>) : BundleValidation
}

/**
 * Semantic validator for a parsed [PluginBundle].
 *
 * Distinct from [PluginBundleParser]: the parser ensures the bundle is
 * *readable*; the validator ensures it is *importable*. Failures here are
 * recoverable by the plugin author (fix the manifest) rather than the host
 * runtime.
 */
class PluginBundleValidator {

    fun validate(bundle: PluginBundle): BundleValidation {
        val reasons = mutableListOf<String>()

        if (bundle.bundleFormatVersion != CURRENT_BUNDLE_FORMAT_VERSION) {
            reasons += "Unsupported bundleFormatVersion ${bundle.bundleFormatVersion}; " +
                "this build understands $CURRENT_BUNDLE_FORMAT_VERSION."
        }

        val manifest = bundle.manifest
        if (manifest.id.isBlank()) reasons += "manifest.id is blank."
        if (manifest.name.isBlank()) reasons += "manifest.name is blank."
        if (manifest.version.isBlank()) reasons += "manifest.version is blank."

        manifest.requiredPermissions.forEachIndexed { index, permission ->
            val permissionReason = validatePermission(permission, index)
            if (permissionReason != null) reasons += permissionReason
        }

        if (bundle.signature != null && bundle.signature.isEmpty()) {
            reasons += "signature.sig is present but empty."
        }

        return if (reasons.isEmpty()) {
            BundleValidation.Ok(permissions = manifest.requiredPermissions.distinct())
        } else {
            BundleValidation.Failed(reasons = reasons.toList())
        }
    }

    private fun validatePermission(permission: PluginPermission, index: Int): String? {
        val (field, value) = when (permission) {
            is PluginPermission.NetworkDomain -> "host" to permission.host
            is PluginPermission.MCPServer -> "uri" to permission.uri
            is PluginPermission.KnowledgeQuery -> "scope" to permission.scope
            is PluginPermission.NativeAction -> "actionId" to permission.actionId
            is PluginPermission.LinkAccess -> "linkId" to permission.linkId
        }
        return if (value.isBlank()) {
            "requiredPermissions[$index] (${permission::class.simpleName}) has blank $field."
        } else {
            null
        }
    }
}
