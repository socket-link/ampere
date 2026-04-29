package link.socket.ampere.plugin.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.ampere.plugin.PluginManifest

class PluginPermissionGateTest {

    private val permission = PluginPermission.NetworkDomain("api.example.com")
    private val manifest = PluginManifest(
        id = "example-plugin",
        name = "Example Plugin",
        version = "1.0.0",
        requiredPermissions = listOf(permission),
    )
    private val toolCall = PluginToolCall(
        pluginId = manifest.id,
        toolId = "fetch-example",
    )

    @Test
    fun `allows when required permission is granted`() {
        val result = PluginPermissionGate.check(
            toolCall = toolCall,
            manifest = manifest,
            userGrants = UserGrants.granted(permission),
        )

        assertEquals(GateResult.Allow, result)
    }

    @Test
    fun `denies when required permission is missing`() {
        val result = PluginPermissionGate.check(
            toolCall = toolCall,
            manifest = manifest,
            userGrants = UserGrants(),
        )

        assertEquals(GateResult.DenyMissing(permission), result)
    }

    @Test
    fun `denies revoked permission before treating it as missing`() {
        val result = PluginPermissionGate.check(
            toolCall = toolCall,
            manifest = manifest,
            userGrants = UserGrants.revoked(permission),
        )

        assertEquals(GateResult.DenyRevoked(permission), result)
    }
}
