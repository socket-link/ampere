package link.socket.ampere.agents.tools.mcp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.data.DEFAULT_JSON

/**
 * McpClient - Protocol-level client for communicating with MCP servers.
 *
 * This class handles the JSON-RPC 2.0 message framing and request/response
 * matching for the MCP protocol. It doesn't handle transport - that's delegated
 * to the connection layer (stdio, http, sse).
 *
 * Key responsibilities:
 * - Serialize requests to JSON
 * - Deserialize responses from JSON
 * - Generate unique request IDs
 * - Match responses to requests
 * - Validate protocol semantics (e.g., initialized before tool calls)
 *
 * The client maintains minimal state:
 * - Whether the connection is initialized
 * - The server info and capabilities (from initialize response)
 *
 * Thread-safety: This class is NOT thread-safe. Callers must synchronize access.
 */
class McpClient(
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * Whether the MCP handshake has completed successfully.
     * Must be true before calling tools/list or tools/call.
     */
    var isInitialized: Boolean = false
        private set

    /**
     * Server information from the initialize response.
     * Null until initialize succeeds.
     */
    var serverInfo: ServerInfo? = null
        private set

    /**
     * Server capabilities from the initialize response.
     * Null until initialize succeeds.
     */
    var serverCapabilities: ServerCapabilities? = null
        private set

    /**
     * The protocol revision the handshake settled on: the server's answer to
     * the [McpProtocolVersion.LATEST] this client offered. Null until
     * initialize succeeds.
     */
    var protocolVersion: String? = null
        private set

    /**
     * Creates an initialize request, offering [McpProtocolVersion.LATEST].
     *
     * This should be the first request sent after connecting to an MCP server.
     */
    fun createInitializeRequest(): McpRequest {
        return McpRequest(
            id = generateRequestId("init"),
            method = "initialize",
            params = json.encodeToJsonElement(
                InitializeParams.serializer(),
                InitializeParams(
                    clientInfo = ClientInfo(
                        name = "Ampere",
                        version = "1.0.0",
                    ),
                ),
            ),
        )
    }

    /**
     * Processes an initialize response.
     *
     * Validates the response and updates internal state if successful.
     *
     * The server answers with the revision it will speak: the one offered if it
     * has it, otherwise one of its own. An answer outside
     * [McpProtocolVersion.SUPPORTED] is a failure, a
     * [McpProtocolVersionException] naming both revisions, and leaves this
     * client uninitialized: carrying on would mean speaking a revision whose
     * rules this client does not know.
     *
     * @param response The response from the server
     * @return Result containing the InitializeResult or error
     */
    fun processInitializeResponse(response: McpResponse): Result<InitializeResult> {
        if (response.isError()) {
            return Result.failure(
                McpProtocolException(
                    "Initialize failed: ${response.error?.message}",
                    response.error,
                ),
            )
        }

        return try {
            val result = json.decodeFromJsonElement(
                InitializeResult.serializer(),
                response.result!!,
            )

            if (!McpProtocolVersion.isSupported(result.protocolVersion)) {
                return Result.failure(
                    McpProtocolVersionException(
                        offered = McpProtocolVersion.LATEST,
                        answered = result.protocolVersion,
                    ),
                )
            }

            // Update state
            isInitialized = true
            serverInfo = result.serverInfo
            serverCapabilities = result.capabilities
            protocolVersion = result.protocolVersion

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to parse initialize response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Creates the `notifications/initialized` notification: the client's half
     * of the handshake, sent once the server's initialize response has been
     * accepted and before any other request.
     *
     * @throws IllegalStateException if not initialized
     */
    fun createInitializedNotification(): McpNotification {
        check(isInitialized) { "Must initialize before sending notifications/initialized" }

        return McpNotification(method = "notifications/initialized")
    }

    /**
     * Creates a tools/list request.
     *
     * This retrieves all tools exposed by the MCP server.
     *
     * @throws IllegalStateException if not initialized
     */
    fun createToolsListRequest(): McpRequest {
        check(isInitialized) { "Must initialize before calling tools/list" }

        return McpRequest(
            id = generateRequestId("tools-list"),
            method = "tools/list",
            params = null, // tools/list takes no parameters
        )
    }

    /**
     * Processes a tools/list response.
     *
     * @param response The response from the server
     * @return Result containing the ToolsListResult or error
     */
    fun processToolsListResponse(response: McpResponse): Result<ToolsListResult> {
        if (response.isError()) {
            return Result.failure(
                McpProtocolException(
                    "Tools list failed: ${response.error?.message}",
                    response.error,
                ),
            )
        }

        return try {
            val result = json.decodeFromJsonElement(
                ToolsListResult.serializer(),
                response.result!!,
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to parse tools/list response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Creates a tools/call request.
     *
     * This invokes a specific tool on the MCP server.
     *
     * @param toolName The name of the tool to invoke
     * @param arguments The tool's input arguments (JSON)
     * @throws IllegalStateException if not initialized
     */
    fun createToolCallRequest(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonElement?,
    ): McpRequest {
        check(isInitialized) { "Must initialize before calling tools" }

        return McpRequest(
            id = generateRequestId("tool-call-$toolName"),
            method = "tools/call",
            params = json.encodeToJsonElement(
                ToolCallParams.serializer(),
                ToolCallParams(
                    name = toolName,
                    arguments = arguments,
                ),
            ),
        )
    }

    /**
     * Processes a tools/call response.
     *
     * @param response The response from the server
     * @return Result containing the ToolCallResult or error
     */
    fun processToolCallResponse(response: McpResponse): Result<ToolCallResult> {
        if (response.isError()) {
            return Result.failure(
                McpProtocolException(
                    "Tool call failed: ${response.error?.message}",
                    response.error,
                ),
            )
        }

        return try {
            val result = json.decodeFromJsonElement(
                ToolCallResult.serializer(),
                response.result!!,
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to parse tool call response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Serializes an MCP request to JSON string.
     */
    fun serializeRequest(request: McpRequest): String {
        return json.encodeToString(McpRequest.serializer(), request)
    }

    /**
     * Serializes an MCP notification to JSON string.
     */
    fun serializeNotification(notification: McpNotification): String {
        return json.encodeToString(McpNotification.serializer(), notification)
    }

    /**
     * Reads one HTTP body or one event's data as the server messages it holds.
     *
     * Usually that is one message. A [McpProtocolVersion.V2025_03_26] server
     * may send several as a JSON array, so this always returns a list. Each is
     * sorted by shape, as JSON-RPC defines it: a `method` and an `id` is a
     * request, a `method` alone is a notification, and anything else is a
     * response.
     */
    fun parseServerMessages(jsonString: String): Result<List<McpServerMessage>> {
        return try {
            val messages = when (val parsed = json.parseToJsonElement(jsonString)) {
                is JsonArray -> parsed.map(::toServerMessage)
                else -> listOf(toServerMessage(parsed))
            }
            Result.success(messages)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to read a message from the server: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    private fun toServerMessage(element: JsonElement): McpServerMessage {
        val message = element as? JsonObject
            ?: throw IllegalArgumentException("a JSON-RPC message must be an object")
        val id = (message["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        return when {
            method != null && id != null -> McpServerMessage.Request(id, method, message["params"])
            method != null -> McpServerMessage.Notification(method, message["params"])
            else -> McpServerMessage.Response(id, message)
        }
    }

    /**
     * Decodes a [McpServerMessage.Response] that [answers][McpServerMessage.Response.id] a request.
     */
    fun decodeResponse(message: McpServerMessage.Response): Result<McpResponse> {
        return try {
            Result.success(json.decodeFromJsonElement(McpResponse.serializer(), message.payload))
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to deserialize response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Deserializes a JSON string to an MCP response.
     */
    fun deserializeResponse(jsonString: String): Result<McpResponse> {
        return try {
            val response = json.decodeFromString(McpResponse.serializer(), jsonString)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to deserialize response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Resets the client state (e.g., after disconnection).
     */
    fun reset() {
        isInitialized = false
        serverInfo = null
        serverCapabilities = null
        protocolVersion = null
    }

    /**
     * Generates a unique request ID.
     */
    private fun generateRequestId(prefix: String): String {
        return "$prefix-${generateUUID(prefix)}"
    }
}

/**
 * Exception thrown when MCP protocol errors occur.
 *
 * @property message Human-readable error message
 * @property mcpError The MCP error details, if available
 * @property cause The underlying cause, if any
 */
open class McpProtocolException(
    message: String,
    val mcpError: McpError? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The server answered `initialize` with a protocol revision this client does
 * not implement (AMPR-514).
 *
 * The message names both revisions, and what this client does speak, so
 * whoever reads it can tell which side has to move.
 *
 * @property offered The revision this client asked for.
 * @property answered The revision the server said it would speak.
 */
class McpProtocolVersionException(
    val offered: String,
    val answered: String,
) : McpProtocolException(
    "The MCP server answered with protocol revision $answered, which this client does not implement. " +
        "It offered $offered and can speak ${McpProtocolVersion.SUPPORTED.joinToString(", ")}.",
)
