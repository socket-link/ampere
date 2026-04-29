package link.socket.ampere.plugin.permission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Declarative permissions a plugin must declare before Ampere dispatches its tools.
 */
@Serializable
sealed interface PluginPermission {

    @Serializable
    @SerialName("network_domain")
    data class NetworkDomain(val host: String) : PluginPermission

    @Serializable
    @SerialName("mcp_server")
    data class MCPServer(val uri: String) : PluginPermission

    @Serializable
    @SerialName("knowledge_query")
    data class KnowledgeQuery(val scope: String) : PluginPermission

    @Serializable
    @SerialName("native_action")
    data class NativeAction(val actionId: String) : PluginPermission

    @Serializable
    @SerialName("link_access")
    data class LinkAccess(val linkId: String) : PluginPermission
}
