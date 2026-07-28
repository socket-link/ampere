package link.socket.ampere.plug.permission

import kotlinx.serialization.Serializable
import link.socket.ampere.plug.PlugManifest

/**
 * Deterministic permission gate that runs before plug-backed tool dispatch.
 */
object PlugPermissionGate {

    fun check(
        toolCall: PlugToolCall,
        manifest: PlugManifest,
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
data class PlugToolCall(
    val plugId: String,
    val toolId: String,
    val requestedPermissions: List<PlugPermission> = emptyList(),
)

@Serializable
data class UserGrants(
    val granted: List<PlugPermission> = emptyList(),
    val revoked: List<PlugPermission> = emptyList(),
) {
    companion object {
        fun granted(vararg permissions: PlugPermission): UserGrants =
            UserGrants(granted = permissions.toList())

        fun revoked(vararg permissions: PlugPermission): UserGrants =
            UserGrants(revoked = permissions.toList())
    }
}

@Serializable
sealed interface GateResult {

    @Serializable
    data object Allow : GateResult

    @Serializable
    data class DenyMissing(val permission: PlugPermission) : GateResult

    @Serializable
    data class DenyRevoked(val permission: PlugPermission) : GateResult
}
