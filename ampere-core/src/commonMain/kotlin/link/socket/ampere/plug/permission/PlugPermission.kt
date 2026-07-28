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
}
