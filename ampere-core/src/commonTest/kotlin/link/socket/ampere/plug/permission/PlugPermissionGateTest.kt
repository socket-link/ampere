package link.socket.ampere.plug.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest

class PlugPermissionGateTest {

    private val permission = PlugPermission.NetworkDomain("api.example.com")
    private val manifest = PlugManifest(
        id = PlugId("example-plug"),
        name = "Example Plug",
        version = "1.0.0",
        requiredPermissions = listOf(permission),
    )
    private val toolCall = PlugToolCall(
        plugId = manifest.id,
        toolId = "fetch-example",
    )

    @Test
    fun `allows when required permission is granted`() {
        val result = PlugPermissionGate.check(
            toolCall = toolCall,
            manifest = manifest,
            userGrants = UserGrants.granted(permission),
        )

        assertEquals(GateResult.Allow, result)
    }

    @Test
    fun `denies when required permission is missing`() {
        val result = PlugPermissionGate.check(
            toolCall = toolCall,
            manifest = manifest,
            userGrants = UserGrants(),
        )

        assertEquals(GateResult.DenyMissing(permission), result)
    }

    @Test
    fun `denies revoked permission before treating it as missing`() {
        val result = PlugPermissionGate.check(
            toolCall = toolCall,
            manifest = manifest,
            userGrants = UserGrants.revoked(permission),
        )

        assertEquals(GateResult.DenyRevoked(permission), result)
    }
}
