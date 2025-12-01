package link.socket.ampere.agents.tools.registry

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpServerId
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolId

/**
 * ToolMetadata - Persistable metadata about a tool.
 *
 * This represents the serializable, database-storable information about a tool.
 * It's separate from the actual [Tool] interface because:
 * - FunctionTool contains non-serializable execution functions
 * - We need to persist tool information across restarts
 * - Agents need to query available tools without loading execution logic
 *
 * ToolMetadata can be reconstructed into:
 * - McpTool (fully reconstructable from metadata)
 * - FunctionTool reference (requires looking up the execution function separately)
 */
@Serializable
data class ToolMetadata(
    /** Unique identifier for this tool */
    val id: ToolId,

    /** Display name of the tool */
    val name: String,

    /** Human-readable description of what the tool does */
    val description: String,

    /** Tool type: "function" or "mcp" */
    val toolType: String,

    /** Minimum autonomy level required to use this tool */
    val requiredAgentAutonomy: AgentActionAutonomy,

    /** For MCP tools: the server ID. Null for function tools. */
    val mcpServerId: McpServerId? = null,

    /** For MCP tools: the remote tool name. Null for function tools. */
    val remoteToolName: String? = null,

    /** JSON schema for tool inputs (primarily for MCP tools) */
    val inputSchema: String? = null,

    /** When this tool was first registered */
    val registeredAt: Instant,

    /** When this tool was last seen/used */
    val lastSeenAt: Instant,
) {
    companion object {
        const val TYPE_FUNCTION = "function"
        const val TYPE_MCP = "mcp"

        /**
         * Create ToolMetadata from a FunctionTool.
         * Note: The execution function itself is not stored, only metadata.
         */
        fun <T : ExecutionContext> fromFunctionTool(tool: FunctionTool<T>, timestamp: Instant): ToolMetadata {
            return ToolMetadata(
                id = tool.id,
                name = tool.name,
                description = tool.description,
                toolType = TYPE_FUNCTION,
                requiredAgentAutonomy = tool.requiredAgentAutonomy,
                mcpServerId = null,
                remoteToolName = null,
                inputSchema = null,
                registeredAt = timestamp,
                lastSeenAt = timestamp,
            )
        }

        /**
         * Create ToolMetadata from an McpTool.
         * MCP tools are fully reconstructable from metadata.
         */
        fun fromMcpTool(tool: McpTool, timestamp: Instant): ToolMetadata {
            return ToolMetadata(
                id = tool.id,
                name = tool.name,
                description = tool.description,
                toolType = TYPE_MCP,
                requiredAgentAutonomy = tool.requiredAgentAutonomy,
                mcpServerId = tool.serverId,
                remoteToolName = tool.remoteToolName,
                inputSchema = tool.inputSchema?.toString(),
                registeredAt = timestamp,
                lastSeenAt = timestamp,
            )
        }

        /**
         * Create ToolMetadata from any Tool instance.
         */
        fun fromTool(tool: Tool<*>, timestamp: Instant): ToolMetadata {
            return when (tool) {
                is McpTool -> fromMcpTool(tool, timestamp)
                is FunctionTool<*> -> fromFunctionTool(tool, timestamp)
            }
        }
    }

    /**
     * Check if this is a function tool.
     */
    fun isFunctionTool(): Boolean = toolType == TYPE_FUNCTION

    /**
     * Check if this is an MCP tool.
     */
    fun isMcpTool(): Boolean = toolType == TYPE_MCP
}
