package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.ToolId

/**
 * ToolEvent - Events related to tool lifecycle, discovery, and execution.
 *
 * These events provide observability into the tool system:
 * - ToolRegistered: A new tool has been discovered and registered in the ToolRegistry
 * - ToolUnregistered: A tool has been removed from the registry (e.g., MCP server disconnected)
 * - ToolDiscoveryComplete: Tool discovery process has finished (e.g., all MCP servers queried)
 *
 * Events are emitted by:
 * - ToolRegistry when tools are registered/unregistered
 * - MCP integration layer when discovery completes
 * - Tool execution layer when tools are invoked
 *
 * Agents can subscribe to these events to:
 * - React to new capabilities becoming available
 * - Handle tool unavailability gracefully
 * - Track tool usage patterns for learning
 */
@Serializable
sealed interface ToolEvent : Event {

    /**
     * A tool has been registered in the ToolRegistry and is now available for agent use.
     *
     * Emitted when:
     * - A FunctionTool is initialized at startup
     * - An MCP server exposes a new tool during discovery
     * - A tool is dynamically added to the registry
     *
     * @property toolId Unique identifier for the tool
     * @property toolName Display name of the tool
     * @property toolType "function" for FunctionTool, "mcp" for McpTool
     * @property requiredAutonomy Minimum autonomy level required to use this tool
     * @property mcpServerId For MCP tools, the server ID; null for function tools
     */
    @Serializable
    data class ToolRegistered(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val toolId: ToolId,
        val toolName: String,
        val toolType: String,
        val requiredAutonomy: AgentActionAutonomy,
        val mcpServerId: String? = null,
    ) : ToolEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Tool registered: $toolName")
            append(" (type: $toolType, autonomy: $requiredAutonomy)")
            mcpServerId?.let { append(" [server: $it]") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "ToolRegistered"
        }
    }

    /**
     * A tool has been unregistered from the ToolRegistry and is no longer available.
     *
     * Emitted when:
     * - An MCP server disconnects and its tools are removed
     * - A tool is explicitly removed from the registry
     * - A tool fails health checks and is marked unavailable
     *
     * @property toolId Unique identifier for the removed tool
     * @property toolName Display name of the tool
     * @property reason Human-readable explanation for removal
     * @property mcpServerId For MCP tools, the server ID; null for function tools
     */
    @Serializable
    data class ToolUnregistered(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val toolId: ToolId,
        val toolName: String,
        val reason: String,
        val mcpServerId: String? = null,
    ) : ToolEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Tool unregistered: $toolName")
            append(" - $reason")
            mcpServerId?.let { append(" [server: $it]") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "ToolUnregistered"
        }
    }

    /**
     * Tool discovery process has completed.
     *
     * Emitted when:
     * - All MCP servers have been queried and their tools registered
     * - Initial tool discovery at system startup finishes
     * - A periodic rediscovery cycle completes
     *
     * This event signals to agents that the tool landscape is stable and they can
     * query the registry to understand available capabilities.
     *
     * @property totalToolsDiscovered Total number of tools now available in registry
     * @property functionToolCount Number of FunctionTools discovered
     * @property mcpToolCount Number of McpTools discovered
     * @property mcpServerCount Number of MCP servers successfully connected
     */
    @Serializable
    data class ToolDiscoveryComplete(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val totalToolsDiscovered: Int,
        val functionToolCount: Int,
        val mcpToolCount: Int,
        val mcpServerCount: Int,
    ) : ToolEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Tool discovery complete: $totalToolsDiscovered tool(s) found")
            append(" ($functionToolCount function, $mcpToolCount MCP)")
            if (mcpServerCount > 0) {
                append(" from $mcpServerCount server(s)")
            }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "ToolDiscoveryComplete"
        }
    }
}
