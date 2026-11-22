package link.socket.ampere.agents.tools.mcp.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * @property params Optional parameters for the method. Left out of the JSON
 *   when null: JSON-RPC allows `params` to be absent, and a present one must be
 *   an object or an array, which `null` is not.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpRequest(
    override val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
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
 * MCP Notification - A one-way message, in either direction.
 *
 * Notifications don't have an id and don't expect a response.
 *
 * @property method The notification method name
 * @property params Optional parameters. Left out of the JSON when null, as
 *   for [McpRequest].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpNotification(
    override val jsonrpc: String = "2.0",
    val method: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val params: JsonElement? = null,
) : McpMessage

/**
 * One JSON-RPC message a server sent, sorted by what it is.
 *
 * A server's stream can carry more than the response a request is waiting
 * for: notifications about the work in progress, and requests of its own.
 * Sorting them is the first step in giving each somewhere to go.
 */
sealed interface McpServerMessage {

    /**
     * An answer to a request.
     *
     * @property id The id of the request it answers, as text, or null for an
     *   error the server could not tie to a request.
     * @property payload The whole response object.
     */
    data class Response(val id: String?, val payload: JsonObject) : McpServerMessage

    /** A one-way message, e.g. `notifications/progress` or `notifications/message`. */
    data class Notification(val method: String, val params: JsonElement?) : McpServerMessage

    /** A request the server makes of the client, e.g. `ping`. */
    data class Request(val id: String, val method: String, val params: JsonElement?) : McpServerMessage
}

/**
 * The revisions of the MCP specification this client speaks (AMPR-514).
 *
 * A revision is named by the date of its last backwards-incompatible change,
 * so revisions order as their names do.
 *
 * The client offers [LATEST] in `initialize` and accepts any of [SUPPORTED] in
 * the server's answer. What each one changes for a tools-only client:
 *
 * - [V2024_11_05]: the handshake, `tools/list`, `tools/call`.
 * - [V2025_03_26]: the Streamable HTTP transport, and JSON-RPC batches, which
 *   this client never sends and reads when a server does.
 * - [V2025_06_18]: no batches, and an `MCP-Protocol-Version` header on every
 *   HTTP request after the handshake.
 *
 * Two newer revisions exist and are deliberately not offered, because offering
 * a revision is a promise to behave as it says:
 *
 * - `2025-11-25` lets a server close a response's event stream before it has
 *   answered and expects the client to resume it with a `GET` and
 *   `Last-Event-ID`. This client does not resume streams.
 * - `2026-07-28` replaces the handshake and the session with per-request
 *   metadata. That is a different client, not a newer header.
 *
 * A server that speaks only one of those answers with it, and the handshake
 * fails with [McpProtocolVersionException] naming both sides.
 */
object McpProtocolVersion {

    const val V2024_11_05: String = "2024-11-05"
    const val V2025_03_26: String = "2025-03-26"
    const val V2025_06_18: String = "2025-06-18"

    /** The newest revision this client implements, and the one it offers. */
    const val LATEST: String = V2025_06_18

    /** Every revision this client accepts from a server, newest first. */
    val SUPPORTED: List<String> = listOf(V2025_06_18, V2025_03_26, V2024_11_05)

    fun isSupported(version: String): Boolean = version in SUPPORTED

    /**
     * Whether HTTP requests after the handshake must carry the
     * `MCP-Protocol-Version` header under [version]. The header arrived with
     * [V2025_06_18]; an older server was never told to expect it.
     */
    fun requiresHttpVersionHeader(version: String): Boolean = version >= V2025_06_18
}

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
    val protocolVersion: String = McpProtocolVersion.LATEST,
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
 * Client capabilities - the optional features this client offers a server.
 *
 * It offers none: no roots, no sampling, no elicitation. That is the empty
 * object `{}` on the wire, which is what this class encodes to. Calling tools
 * is not a client capability; a server declares `tools`, a client just calls
 * them.
 *
 * @property experimental Non-standard capabilities, by name. Left out of the
 *   JSON when null, since a server may reject a `null` where an object belongs.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientCapabilities(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val experimental: JsonObject? = null,
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
 * @property description Human-readable description of what the tool does.
 *   Optional in the protocol, so a tool that gives none has an empty one here
 *   rather than failing the whole `tools/list`.
 * @property inputSchema JSON schema describing the tool's input parameters
 */
@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null,
)

/**
 * Tool call request parameters.
 *
 * Sent to invoke a specific tool on the MCP server.
 *
 * @property name The name of the tool to invoke
 * @property arguments The input arguments (must match the tool's inputSchema).
 *   Left out of the JSON when null.
 * @property meta The request's `_meta`: where a caller that wants progress
 *   says so. Left out of the JSON when null.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolCallParams(
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val arguments: JsonElement? = null,
    @SerialName("_meta")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val meta: RequestMeta? = null,
)

/**
 * A request's `_meta`, as far as this client uses it.
 *
 * @property progressToken Asks the server for `notifications/progress` about
 *   this request, each carrying this token. A server is not obliged to send
 *   any. Must be unique among the requests in flight; this client uses the
 *   request's own id.
 */
@Serializable
data class RequestMeta(
    val progressToken: String,
)

/**
 * The params of a `notifications/cancelled`: the sender no longer wants the
 * answer to a request it made.
 *
 * @property requestId The id of the request being cancelled.
 * @property reason Why, for the receiver's log.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CancelledParams(
    val requestId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reason: String? = null,
)

/**
 * The params of a `notifications/progress`.
 *
 * @property progressToken The token of the request the progress is about. A
 *   string or a number on the wire; kept as the primitive it arrived as.
 * @property progress How far along, in whatever unit the server counts. It
 *   only ever increases.
 * @property total What [progress] is counting towards, when the server knows.
 * @property message What is happening, in the server's words.
 */
@Serializable
data class ProgressParams(
    val progressToken: JsonPrimitive,
    val progress: Double,
    val total: Double? = null,
    val message: String? = null,
)

/**
 * The params of a `notifications/message`: one log message from the server.
 *
 * @property level One of the syslog severities (`debug`, `info`, `notice`,
 *   `warning`, `error`, `critical`, `alert`, `emergency`). Kept as text, so a
 *   level this client has not heard of is still delivered.
 * @property logger The name of the server-side logger, if it gave one.
 * @property data The message: any JSON the server chose to send.
 */
@Serializable
data class LoggingMessageParams(
    val level: String,
    val logger: String? = null,
    val data: JsonElement = JsonNull,
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
