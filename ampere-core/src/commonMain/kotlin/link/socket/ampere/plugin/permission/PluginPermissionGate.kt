package link.socket.ampere.plugin.permission

import kotlinx.serialization.Serializable
import link.socket.ampere.plugin.PluginManifest

/**
 * Deterministic permission gate that runs before plugin-backed tool dispatch.
 */
object PluginPermissionGate {

    fun check(
        toolCall: PluginToolCall,
        manifest: PluginManifest,
        userGrants: UserGrants,
    ): GateResult {
        val requiredPermissions = (manifest.requiredPermissions + toolCall.requestedPermissions).distinct()

        requiredPermissions.forEach { permission ->
            if (permission in userGrants.revoked) {
                return GateResult.DenyRevoked(permission)
            }

            if (permission !in userGrants.granted) {
                return GateResult.DenyMissing(permission)
            }
        }

        return GateResult.Allow
    }
}

@Serializable
data class PluginToolCall(
    val pluginId: String,
    val toolId: String,
    val requestedPermissions: List<PluginPermission> = emptyList(),
)

@Serializable
data class UserGrants(
    val granted: List<PluginPermission> = emptyList(),
    val revoked: List<PluginPermission> = emptyList(),
) {
    companion object {
        fun granted(vararg permissions: PluginPermission): UserGrants =
            UserGrants(granted = permissions.toList())

        fun revoked(vararg permissions: PluginPermission): UserGrants =
            UserGrants(revoked = permissions.toList())
    }
}

@Serializable
sealed interface GateResult {

    @Serializable
    data object Allow : GateResult

    @Serializable
    data class DenyMissing(val permission: PluginPermission) : GateResult

    @Serializable
    data class DenyRevoked(val permission: PluginPermission) : GateResult
}
