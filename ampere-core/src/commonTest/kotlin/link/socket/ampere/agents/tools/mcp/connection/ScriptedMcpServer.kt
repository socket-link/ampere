package link.socket.ampere.agents.tools.mcp.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A Streamable HTTP MCP server reduced to its decisions: given a request's
 * method, headers and body, what status, headers and body come back (AMPR-514).
 *
 * It refuses what a spec-current server refuses, which is what makes it a
 * fixture rather than an echo: a `GET` (405), a `POST` that does not accept both
 * response shapes (406), a request that should name its session and does not
 * (400) or names one that has ended (404), and a request missing the
 * protocol-version header the negotiated revision requires (400).
 *
 * It holds no socket. [ScriptedMcpTransport] answers a connection from it
 * directly, on every platform; the JVM tests put the same object behind a real
 * HTTP server, so the engine's own behaviour is on the path too.
 *
 * Everything it issues is obviously fake. Not thread-safe beyond what the JVM
 * fixture needs: [handle] is synchronized there.
 */
internal class ScriptedMcpServer(
    var mode: Mode = Mode.JSON,
    var issuesSessions: Boolean = true,
    var supportedVersions: List<String> = listOf("2025-06-18", "2025-03-26", "2024-11-05"),
    var requiredBearer: String? = null,
    var allowsDelete: Boolean = true,
) {

    /** The shape this server answers a request in. */
    enum class Mode { JSON, EVENT_STREAM }

    /** One request as the server saw it. Header names are lower-cased. */
    class Received(val httpMethod: String, headers: Map<String, String>, val body: String?) {
        val headers: Map<String, String> = headers.mapKeys { (name, _) -> name.lowercase() }
        val message: JsonObject? = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val rpcMethod: String? = message?.get("method")?.jsonPrimitive?.content
        val rpcId: String? = message?.get("id")?.jsonPrimitive?.content
    }

    /**
     * What the server answers.
     *
     * @property body A whole body, for a JSON answer.
     * @property events Server-sent events in wire form, for a stream. Each is
     *   written and flushed on its own.
     * @property holdOpen For a stream: leave it open after the last event, as a
     *   server may. Only the HTTP fixture can act on this.
     * @property stall Send nothing at all, so the request runs into its time limit.
     * @property delayMs Wait this long before answering.
     */
    class Reply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val events: List<String>? = null,
        val holdOpen: Boolean = false,
        val stall: Boolean = false,
        val delayMs: Long = 0,
    )

    /** Every request received, in order. */
    val received = mutableListOf<Received>()

    /** The session a request must name, or null when none is live. */
    var liveSession: String? = null
        private set

    private var sessionsIssued = 0
    private var negotiated: String? = null

    /** Answer `initialize` with this revision whatever was offered. */
    var forcedVersion: String? = null

    /** Answer every request in a session with 404, however fresh the session. */
    var endsEverySession = false

    /** In [Mode.EVENT_STREAM], close a `tools/call` stream after its notification and before its response. */
    var closesStreamBeforeResponse = false

    /** In [Mode.EVENT_STREAM], leave a `tools/call` stream open after its response. */
    var holdsStreamOpen = false

    /** In [Mode.JSON], answer with a one-element JSON array, as a `2025-03-26` server may. */
    var batchesResponses = false

    /** Answer `tools/call` with an error that names no request, as a server that could not parse one does. */
    var answersWithoutId = false

    /** Never answer `tools/call`. */
    var stallsToolCalls = false

    /** Answer `tools/call` with this HTTP status and no body. */
    var toolCallStatus: Int? = null

    /** Take this long to answer `tools/call` (AMPR-515). */
    var toolCallDelayMs = 0L

    /**
     * In [Mode.EVENT_STREAM], report progress on a `tools/call` and then go quiet:
     * no response, and the stream left open. What a long-running tool looks like
     * to a caller that is about to give up on it.
     */
    var goesQuietAfterProgress = false

    /** Never answer a `notifications/cancelled`, as a server that has stopped responding does not. */
    var stallsCancellations = false

    /** The request ids the server has been told are cancelled, in order. */
    fun cancelledRequestIds(): List<String> = received("notifications/cancelled").map { cancelled ->
        cancelled.message!!["params"]!!.jsonObject["requestId"]!!.jsonPrimitive.content
    }

    /** Forget the live session without telling the client, as a server restart does. */
    fun endSession() {
        liveSession = null
    }

    /** Requests received, by JSON-RPC method. */
    fun received(rpcMethod: String): List<Received> = received.filter { it.rpcMethod == rpcMethod }

    fun handle(httpMethod: String, headers: Map<String, String>, body: String?): Reply {
        val request = Received(httpMethod, headers, body)
        received += request

        val bearer = requiredBearer
        if (bearer != null && request.headers["authorization"] != "Bearer $bearer") {
            return Reply(401, mapOf("WWW-Authenticate" to CHALLENGE))
        }

        return when (httpMethod) {
            "GET" -> Reply(405, mapOf("Allow" to "POST, DELETE"))
            "DELETE" -> delete(request)
            "POST" -> post(request)
            else -> Reply(405, mapOf("Allow" to "POST, DELETE"))
        }
    }

    private fun post(request: Received): Reply {
        val accept = request.headers["accept"].orEmpty()
        if ("application/json" !in accept || "text/event-stream" !in accept) return Reply(406)

        val rpcMethod = request.rpcMethod ?: return Reply(400)
        if (rpcMethod == "initialize") return initialize(request)

        sessionProblem(request)?.let { return it }

        val id = request.rpcId
        return when {
            rpcMethod == "notifications/initialized" -> Reply(202)
            rpcMethod == "notifications/cancelled" && stallsCancellations -> Reply(202, stall = true)
            id == null -> Reply(202)
            rpcMethod == "tools/list" -> respond(response(id, TOOLS))
            rpcMethod == "tools/call" -> toolCall(id, request)
            else -> respond(error(id, -32601, "Method not found: $rpcMethod"))
        }
    }

    private fun initialize(request: Received): Reply {
        // A new session is opened without the id of an old one.
        if ("mcp-session-id" in request.headers) return Reply(400)

        val offered = request.message!!["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        val version = forcedVersion ?: offered.takeIf { it in supportedVersions } ?: supportedVersions.first()
        negotiated = version

        val headers = buildMap {
            if (issuesSessions) {
                sessionsIssued += 1
                val issued = "fixture-session-$sessionsIssued"
                liveSession = issued
                put("Mcp-Session-Id", issued)
            }
        }
        val result = buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "ScriptedMcpServer")
                put("version", "0.0.0")
            }
        }
        return respond(response(request.rpcId!!, result), headers)
    }

    /** Why a request after the handshake is refused before it is read, or null when it is not. */
    private fun sessionProblem(request: Received): Reply? {
        if (issuesSessions) {
            val named = request.headers["mcp-session-id"] ?: return Reply(400)
            if (endsEverySession || named != liveSession) return Reply(404)
        }
        val version = negotiated
        if (version != null && version >= "2025-06-18" && request.headers["mcp-protocol-version"] != version) {
            return Reply(400)
        }
        return null
    }

    private fun toolCall(id: String, request: Received): Reply {
        if (stallsToolCalls) return Reply(200, stall = true)
        toolCallStatus?.let { return Reply(it) }

        if (answersWithoutId) {
            return respond(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32600)
                        put("message", "Invalid Request")
                    }
                },
            )
        }

        val params = request.message!!["params"]!!.jsonObject
        val result = buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "called ${params["name"]!!.jsonPrimitive.content}")
                    },
                )
            }
            put("isError", false)
        }
        val answer = response(id, result)

        if (mode == Mode.JSON) return respond(answer, delayMs = toolCallDelayMs)

        // Progress is reported only to a caller that asked for it, under the token it gave.
        val token = params["_meta"]?.jsonObject?.get("progressToken")?.jsonPrimitive?.content
        val quiet = goesQuietAfterProgress
        val events = buildList {
            // A comment, and an event that carries only an id: neither is a message.
            add(": keep-alive\n\n")
            add("id: fixture-event-0\ndata:\n\n")
            if (token != null) add(event(progress(token, 1.0, "one of two")))
            // Progress about some other call, and progress about none: not this caller's.
            add(event(progress("someone-elses-token", 50.0, "not yours")))
            add(event(progress(null, 99.0, "nobody's")))
            add(event(LOG_NOTIFICATION))
            if (token != null) add(event(progress(token, 2.0, "two of two")))
            if (!closesStreamBeforeResponse && !quiet) add(event(answer))
        }
        return Reply(
            status = 200,
            headers = mapOf("Content-Type" to "text/event-stream"),
            events = events,
            holdOpen = holdsStreamOpen || quiet,
            delayMs = toolCallDelayMs,
        )
    }

    private fun progress(token: String?, progress: Double, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/progress")
        putJsonObject("params") {
            if (token != null) put("progressToken", token)
            put("progress", progress)
            put("total", 2.0)
            put("message", message)
        }
    }

    private fun delete(request: Received): Reply = when {
        !allowsDelete -> Reply(405, mapOf("Allow" to "POST"))
        request.headers["mcp-session-id"] != liveSession -> Reply(404)
        else -> {
            liveSession = null
            Reply(204)
        }
    }

    private fun respond(
        message: JsonObject,
        headers: Map<String, String> = emptyMap(),
        delayMs: Long = 0,
    ): Reply = when (mode) {
        Mode.JSON -> {
            val body: JsonElement = if (batchesResponses) buildJsonArray { add(message) } else message
            Reply(200, headers + ("Content-Type" to "application/json"), body = body.toString(), delayMs = delayMs)
        }

        Mode.EVENT_STREAM ->
            Reply(200, headers + ("Content-Type" to "text/event-stream"), events = listOf(event(message)))
    }

    private fun response(id: String, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: String, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun event(message: JsonObject): String = "event: message\ndata: $message\n\n"

    companion object {

        /** What the 401 says about where a credential comes from. A reserved, unresolvable host. */
        const val CHALLENGE: String =
            "Bearer resource_metadata=\"https://mcp.example.test/.well-known/oauth-protected-resource\""

        /** Two tools; the second gives no description, which the protocol allows. */
        private val TOOLS: JsonObject = buildJsonObject {
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put("name", "echo")
                        put("description", "Says what it was called with")
                        putJsonObject("inputSchema") { put("type", "object") }
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "undescribed")
                        putJsonObject("inputSchema") { put("type", "object") }
                    },
                )
            }
        }

        private val LOG_NOTIFICATION: JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/message")
            putJsonObject("params") {
                put("level", "info")
                put("data", "working on it")
            }
        }
    }
}

/**
 * An [McpHttpTransport] that answers from a [ScriptedMcpServer] with no network
 * in between, so the connection's protocol logic runs on every platform's tests.
 */
internal class ScriptedMcpTransport(private val server: ScriptedMcpServer) : McpHttpTransport {

    /** Every request the connection sent, in order. */
    val requests = mutableListOf<McpHttpRequest>()

    var closed = false
        private set

    override suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T> = try {
        requests += request
        val contentType = if (request.body == null) emptyMap() else mapOf("Content-Type" to "application/json")
        val reply = server.handle(request.method.name, request.headers + contentType, request.body)

        // The contract of a real transport: an exchange that outlives its limit is a timeout.
        val handled = withTimeoutOrNull(request.timeoutMs) {
            if (reply.stall) awaitCancellation()
            delay(reply.delayMs)
            Handled(handle(reply.asResponse()))
        }
        if (handled == null) {
            Result.failure(McpRequestTimeoutException(request.timeoutMs))
        } else {
            Result.success(handled.value)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun close(): Result<Unit> {
        closed = true
        return Result.success(Unit)
    }

    private class Handled<T>(val value: T)

    private fun ScriptedMcpServer.Reply.asResponse(): McpHttpResponse {
        val text = body ?: events.orEmpty().joinToString("")
        // A stream's text ends with a newline, so its last "line" is the empty remainder.
        val lines = ArrayDeque(if (events == null) text.lines() else text.lines().dropLast(1))
        return McpHttpResponse(
            status = status,
            headers = headers,
            body = object : McpHttpBody {
                // A stream the server holds open has no end to read: the reader waits.
                override suspend fun readLine(): String? =
                    lines.removeFirstOrNull() ?: if (holdOpen) awaitCancellation() else null

                override suspend fun readText(): String = text
            },
        )
    }
}
