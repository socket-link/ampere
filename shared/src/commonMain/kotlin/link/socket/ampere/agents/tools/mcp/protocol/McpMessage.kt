package link.socket.ampere.agents.tools.mcp.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP Protocol Messages - JSON-RPC 2.0 based message types for MCP communication.
 *
 * The Model Context Protocol (MCP) uses JSON-RPC 2.0 as its underlying communication
 * mechanism. All messages follow the JSON-RPC spec with specific method names and
 * parameters defined by MCP.
 *
 * Key MCP methods:
 * - initialize: Handshake to establish connection and exchange capabilities
 * - tools/list: Get the list of available tools from the server
 * - tools/call: Invoke a specific tool with parameters
 */

/**
 * Base sealed interface for all MCP messages.
 */
@Serializable
sealed interface McpMessage {
    /** JSON-RPC protocol version (always "2.0") */
    val jsonrpc: String
}

/**
 * MCP Request - A JSON-RPC request sent to the server.
 *
 * @property id Unique identifier for this request (used to match responses)
 * @property method The RPC method name (e.g., "initialize", "tools/list")
 * @property params Optional parameters for the method
 */
@Serializable
data class McpRequest(
    override val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
) : McpMessage

/**
 * MCP Response - A JSON-RPC response from the server.
 *
 * Either contains a successful result or an error (but not both).
 *
 * @property id The request ID this response corresponds to
 * @property result The successful result (null if error occurred)
 * @property error The error details (null if successful)
 */
@Serializable
data class McpResponse(
    override val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: McpError? = null,
) : McpMessage {
    /**
     * Whether this response indicates success.
     */
    fun isSuccess(): Boolean = error == null && result != null

    /**
     * Whether this response indicates an error.
     */
    fun isError(): Boolean = error != null
}

/**
 * MCP Error - Details about an error that occurred during RPC call.
 *
 * @property code Error code (standard JSON-RPC error codes)
 * @property message Human-readable error message
 * @property data Optional additional error data
 */
@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        // Standard JSON-RPC error codes
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // MCP-specific error codes
        const val SERVER_NOT_INITIALIZED = -32001
        const val TOOL_NOT_FOUND = -32002
        const val TOOL_EXECUTION_FAILED = -32003
    }
}

/**
 * MCP Notification - A one-way notification from server to client.
 *
 * Notifications don't have an id and don't expect a response.
 *
 * @property method The notification method name
 * @property params Optional parameters
 */
@Serializable
data class McpNotification(
    override val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
) : McpMessage

// ==================== MCP-Specific Request Types ====================

/**
 * Initialize request parameters.
 *
 * Sent as the first message to establish a connection with the MCP server.
 *
 * @property protocolVersion The MCP protocol version the client supports
 * @property clientInfo Information about the client
 * @property capabilities Client capabilities
 */
@Serializable
data class InitializeParams(
    val protocolVersion: String = "2024-11-05", // MCP protocol version
    val clientInfo: ClientInfo,
    val capabilities: ClientCapabilities = ClientCapabilities(),
)

/**
 * Information about the client.
 */
@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
)

/**
 * Client capabilities - what the client supports.
 *
 * For now, this is minimal, but can be extended to support:
 * - Sampling (LLM-powered agent interactions)
 * - Resources (file/data access)
 * - Prompts (pre-defined prompt templates)
 */
@Serializable
data class ClientCapabilities(
    val tools: ToolCapabilities = ToolCapabilities(),
)

/**
 * Tool-related capabilities.
 */
@Serializable
data class ToolCapabilities(
    val supportsProgress: Boolean = false,
)

/**
 * Initialize response result.
 *
 * Contains server information and capabilities.
 */
@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val serverInfo: ServerInfo,
    val capabilities: ServerCapabilities,
)

/**
 * Information about the server.
 */
@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
)

/**
 * Server capabilities - what the server supports.
 */
@Serializable
data class ServerCapabilities(
    val tools: ToolCapabilities? = null,
)

/**
 * Tools list response result.
 *
 * Contains all tools exposed by the server.
 */
@Serializable
data class ToolsListResult(
    val tools: List<McpToolDescriptor>,
)

/**
 * Descriptor for a tool exposed by an MCP server.
 *
 * This is the MCP protocol's representation of a tool.
 * It gets converted to our internal McpTool type.
 *
 * @property name The tool's unique name
 * @property description Human-readable description of what the tool does
 * @property inputSchema JSON schema describing the tool's input parameters
 */
@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject? = null,
)

/**
 * Tool call request parameters.
 *
 * Sent to invoke a specific tool on the MCP server.
 *
 * @property name The name of the tool to invoke
 * @property arguments The input arguments (must match the tool's inputSchema)
 */
@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonElement? = null,
)

/**
 * Tool call response result.
 *
 * Contains the result of executing a tool.
 *
 * @property content Array of content items returned by the tool
 * @property isError Whether the tool execution resulted in an error
 */
@Serializable
data class ToolCallResult(
    val content: List<ContentItem> = emptyList(),
    val isError: Boolean = false,
)

/**
 * Content item - a piece of content returned by a tool.
 *
 * Tools can return multiple content items of different types.
 *
 * @property type The type of content ("text", "image", "resource", etc.)
 * @property text For text content: the actual text
 * @property mimeType For binary content: the MIME type
 * @property data For binary content: base64-encoded data
 */
@Serializable
data class ContentItem(
    val type: String,
    val text: String? = null,
    val mimeType: String? = null,
    val data: String? = null,
)
