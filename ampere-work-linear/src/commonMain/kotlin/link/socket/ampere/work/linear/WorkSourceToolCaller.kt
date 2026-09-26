package link.socket.ampere.work.linear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.mcp.McpClient

/**
 * The MCP surface this adapter calls, narrowed to the two operations it needs.
 *
 * A seam, not an abstraction over MCP: the production implementation
 * ([McpWorkSourceToolCaller]) forwards straight to
 * [link.socket.ampere.mcp.McpClient], and the types crossing it are MCP's own
 * ([McpToolDescriptor], [ToolCallResult]) rather than adapter-shaped ones. That
 * is deliberate — a test double here stands in for the *wire*, so the response
 * unwrapping in [jsonBody], which is itself vendor surface, is exercised by
 * every test rather than bypassed by them.
 */
interface WorkSourceToolCaller {

    /** The server's advertised tool surface, for [WorkSourceToolPins.verify]. */
    suspend fun listTools(): Result<List<McpToolDescriptor>>

    /** Invoke one tool by its pinned name. */
    suspend fun call(tool: String, arguments: JsonObject): Result<ToolCallResult>
}

/** Forwards to a connected [McpClient]. The production path. */
class McpWorkSourceToolCaller(
    private val client: McpClient,
) : WorkSourceToolCaller {

    override suspend fun listTools(): Result<List<McpToolDescriptor>> = client.listTools()

    override suspend fun call(tool: String, arguments: JsonObject): Result<ToolCallResult> =
        client.callTool(tool, arguments)
}

/**
 * The work source answers in JSON-as-text: one `text` content item holding the
 * whole response object.
 *
 * Unwrapping it is the narrowest place vendor drift can hide, so it is one
 * function with typed failures rather than a `?.let` chain at each call site. An
 * [ToolCallResult.isError] result becomes [WorkSourceFailure.ToolCallFailed]
 * carrying whatever text the server sent; anything else unreadable becomes
 * [WorkSourceFailure.MalformedResponse]. Neither is ever null-coalesced into an
 * empty response — an empty page and a page nobody could read must not look
 * alike.
 */
internal fun ToolCallResult.jsonBody(tool: String): Result<JsonObject> {
    errorOrNull(tool)?.let { return workSourceFailure(it) }

    val text = textContent

    if (text == null) {
        return workSourceFailure(
            WorkSourceFailure.MalformedResponse(
                tool = tool,
                reason = "no '$TEXT_CONTENT' content item; got ${content.map { it.type }}",
            ),
        )
    }

    val element = runCatching { WORK_SOURCE_JSON.parseToJsonElement(text) }.getOrElse { error ->
        return workSourceFailure(
            WorkSourceFailure.MalformedResponse(tool, "body is not JSON: ${error.message}"),
        )
    }

    return (element as? JsonObject)?.let { Result.success(it) }
        ?: workSourceFailure(
            WorkSourceFailure.MalformedResponse(tool, "body is not a JSON object"),
        )
}

/** Drops null arguments, so an unset optional is absent rather than an explicit null. */
internal fun toolArguments(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

internal fun String?.asJson(): JsonPrimitive? = this?.let { JsonPrimitive(it) }

internal fun Int?.asJson(): JsonPrimitive? = this?.let { JsonPrimitive(it) }

internal fun Boolean?.asJson(): JsonPrimitive? = this?.let { JsonPrimitive(it) }

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * The server's error, when it reported one.
 *
 * Split out from [jsonBody] for the write path: a write whose *response* will not
 * parse still landed, and reporting it as a failure would have a caller retry a
 * claim comment it already posted. The sink checks this, then reads the body
 * best-effort onto [link.socket.ampere.plug.spi.ExecuteReceipt.postWriteState].
 */
internal fun ToolCallResult.errorOrNull(tool: String): WorkSourceFailure? =
    if (isError) {
        WorkSourceFailure.ToolCallFailed(
            tool = tool,
            reason = textContent ?: "the server reported an error with no text content",
        )
    } else {
        null
    }

private val ToolCallResult.textContent: String?
    get() = content.firstOrNull { it.type == TEXT_CONTENT && it.text != null }?.text

private const val TEXT_CONTENT = "text"

internal val WORK_SOURCE_JSON: Json = Json { ignoreUnknownKeys = true }
