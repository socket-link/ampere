package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.plugin.permission.PluginPermission

@Serializable
enum class PermissionDeniedReason {
    MISSING_GRANT,
    REVOKED_GRANT,
}

@Serializable
@SerialName("PermissionDeniedEvent")
data class PermissionDeniedEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.HIGH,
    val pluginId: String,
    val toolId: ToolId,
    val toolName: String,
    val permission: PluginPermission,
    val reason: PermissionDeniedReason,
) : Event {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("Permission denied: $toolName ($toolId)")
        append(" plugin=$pluginId")
        append(" reason=$reason")
        append(" permission=$permission")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "PermissionDenied"
    }
}
