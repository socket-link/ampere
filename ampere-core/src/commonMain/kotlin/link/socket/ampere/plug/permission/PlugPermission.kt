package link.socket.ampere.plug.permission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Declarative permissions a plug must declare before Ampere dispatches its tools.
 */
@Serializable
sealed interface PlugPermission {

    @Serializable
    @SerialName("network_domain")
    data class NetworkDomain(val host: String) : PlugPermission

    @Serializable
    @SerialName("mcp_server")
    data class MCPServer(val uri: String) : PlugPermission

    @Serializable
    @SerialName("knowledge_query")
    data class KnowledgeQuery(val scope: String) : PlugPermission

    @Serializable
    @SerialName("native_action")
    data class NativeAction(val actionId: String) : PlugPermission

    @Serializable
    @SerialName("link_access")
    data class LinkAccess(val linkId: String) : PlugPermission

    /**
     * A device/OS capability the plug needs (e.g. `"calendar"`,
     * `"contacts_read"`, `"location"`). Unlike the other variants, this is
     * not an Ampere-internal grant: authorizing it is an OS-level decision
     * the user makes outside Ampere (an EventKit/HealthKit/CoreLocation
     * prompt, etc.), reported back via [NativeAuthorizationStatus].
     *
     * The token vocabulary matches Socket's `PlugPermission.parse` tokens
     * so a manifest declaring `device_capability` round-trips identically
     * on both sides of the wire.
     */
    @Serializable
    @SerialName("device_capability")
    data class DeviceCapability(val capability: String) : PlugPermission
}
