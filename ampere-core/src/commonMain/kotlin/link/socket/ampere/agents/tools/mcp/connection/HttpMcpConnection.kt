package link.socket.ampere.agents.tools.mcp.connection

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpClient
import link.socket.ampere.agents.tools.mcp.protocol.McpProtocolException
import link.socket.ampere.agents.tools.mcp.protocol.McpProtocolVersion
import link.socket.ampere.agents.tools.mcp.protocol.McpRequest
import link.socket.ampere.agents.tools.mcp.protocol.McpResponse
import link.socket.ampere.agents.tools.mcp.protocol.McpServerMessage
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * HttpMcpConnection - MCP connection over the Streamable HTTP transport (AMPR-514).
 *
 * Every JSON-RPC message is its own HTTP `POST` to the one MCP endpoint, and the
 * server chooses the shape of each answer:
 *
 * - `Content-Type: application/json`: the body is the response.
 * - `Content-Type: text/event-stream`: the body is a stream of server-sent
 *   events, read as it arrives until the event that answers this request. The
 *   server may send other messages first. Progress about a tool call and the
 *   server's log messages become [McpCallEvent]s for a caller that collects
 *   [invokeToolStreaming]; anything else (a request of the server's own,
 *   progress for another call) is logged at debug.
 *
 * What the transport requires of a client, and where this class does it:
 *
 * - **`Accept: application/json, text/event-stream` on every `POST`.** A server
 *   may refuse a request that does not list both.
 * - **[connect] sends nothing.** The transport has no request that only tests
 *   reachability (a `GET` asks for a server-initiated stream, and a server may
 *   answer it 405), so whether the server can be reached is whatever [initialize]
 *   reports. A 401 there is still [McpUnauthorizedException].
 * - **Protocol revision.** [initialize] offers [McpProtocolVersion.LATEST] and
 *   accepts any revision in [McpProtocolVersion.SUPPORTED]; another answer is
 *   [link.socket.ampere.agents.tools.mcp.protocol.McpProtocolVersionException].
 *   Later requests carry `MCP-Protocol-Version` when the revision agreed on
 *   defines that header.
 * - **Session.** If the handshake's response carries `Mcp-Session-Id`, every
 *   later request carries it too. A 404 to such a request means the server ended
 *   the session: the connection opens a new one, once, and sends the request
 *   again; a second 404 is [McpSessionExpiredException]. A 404 is the server
 *   refusing a request before acting on it, so the repeat cannot land twice.
 *   [disconnect] ends the session with a `DELETE`, which a server may decline.
 * - **`notifications/initialized`** follows a successful handshake, as the
 *   lifecycle requires.
 *
 * Requests are not serialized against each other: a tool call that takes
 * minutes does not hold up a second call on the same connection. Only the
 * handshake and the session it produces are guarded.
 *
 * ### Tool calls (AMPR-515)
 *
 * [invokeTool] and [invokeToolStreaming] are one call. The streaming form asks
 * the server for progress and yields what arrives; the plain form does not ask
 * and returns only the ending. Both:
 *
 * - **Run under a time limit of the caller's choosing** ([McpCallOptions]),
 *   defaulting to [McpServerConfiguration.timeoutMs]. It is a ceiling on the
 *   whole call, so a tool that runs for minutes is called with minutes.
 * - **Tell the server when the answer is no longer wanted.** If the caller is
 *   cancelled, or the limit runs out, the connection sends
 *   `notifications/cancelled` for the request and waits at most
 *   [CANCEL_GRACE_MS] for it to be delivered, so a dead server cannot hold a
 *   cancellation. Dropping the HTTP request is not a cancellation by the
 *   transport's rules; a server keeps working until it is told. The response
 *   stream itself cannot be held open past the cancellation (the HTTP engine
 *   lets go of it at once), so the notification travels as its own `POST`.
 *
 * Nothing here logs a credential. The bearer token is only ever a header value,
 * and a session id is logged as its first characters and its length.
 *
 * Platform-specific implementation: the HTTP engine comes from
 * [HttpClientHandler]; everything about the wire is written once, here and in
 * [KtorMcpHttpTransport].
 */
class HttpMcpConnection(
    private val config: McpServerConfiguration,
    private val httpHandler: McpHttpTransport,
    private val logger: Logger = Logger.withTag("HttpMcpConnection"),
) : McpServerConnection {

    override val serverId: String = config.id

    private val mcpClient = McpClient()

    /** Guards [connected] and [session]: the handshake, and the replacement of a session that ended. */
    private val lifecycle = Mutex()

    private var connected = false

    /** What the last successful handshake agreed on. Null before [initialize] and after [disconnect]. */
    private var session: Session? = null

    override val isConnected: Boolean
        get() = connected

    /**
     * Readies the transport. Nothing is sent: see the class documentation.
     */
    override suspend fun connect(): Result<Unit> = lifecycle.withLock {
        if (!connected) {
            connected = true
            logger.i { "Ready to reach HTTP MCP server ${config.displayName} at ${config.endpoint}" }
        }
        Result.success(Unit)
    }

    /**
     * Performs the MCP initialize handshake: `initialize`, the revision check,
     * the session id, then `notifications/initialized`.
     */
    override suspend fun initialize(): Result<InitializeResult> = lifecycle.withLock {
        check(connected) { "Must connect() before initialize()" }

        logger.i { "Initializing MCP handshake with ${config.displayName}..." }

        handshake(replacing = session)
            .onSuccess { (opened, result) ->
                session = opened
                logger.i {
                    "MCP handshake successful with ${config.displayName}: " +
                        "${result.serverInfo.name} v${result.serverInfo.version}, " +
                        "protocol ${opened.protocolVersion}, ${opened.describeSession()}"
                }
            }
            .onFailure { error ->
                logger.e(error) { "MCP handshake failed with ${config.displayName}" }
            }
            .map { (_, result) -> result }
    }

    /**
     * Queries available tools.
     */
    override suspend fun listTools(): Result<List<McpToolDescriptor>> {
        val current = currentSession("listTools()")

        logger.i { "Listing tools from ${config.displayName}..." }

        val request = mcpClient.createToolsListRequest()
        return call(request, current, config.timeoutMs) { message -> logUnrouted(request, message) }
            .mapCatching { response -> mcpClient.processToolsListResponse(response).getOrThrow() }
            .map { result ->
                logger.i { "Discovered ${result.tools.size} tools from ${config.displayName}" }
                result.tools
            }.onFailure { error ->
                logger.e(error) { "Failed to list tools from ${config.displayName}" }
            }
    }

    /**
     * Invokes a tool and returns how it ended. No progress is asked for. See
     * "Tool calls" on the class for what it shares with [invokeToolStreaming].
     */
    override suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult> =
        toolCall(toolName, arguments, McpCallOptions.Default, wantsProgress = false).awaitResult()

    /**
     * Invokes a tool, asking the server for progress, and yields what it
     * reports until the one terminal event. See "Tool calls" on the class.
     */
    override fun invokeToolStreaming(
        toolName: String,
        arguments: JsonElement?,
        options: McpCallOptions,
    ): Flow<McpCallEvent> = toolCall(toolName, arguments, options, wantsProgress = true)

    private fun toolCall(
        toolName: String,
        arguments: JsonElement?,
        options: McpCallOptions,
        wantsProgress: Boolean,
    ): Flow<McpCallEvent> = flow {
        val current = currentSession("invokeTool()")
        val request = mcpClient.createToolCallRequest(toolName, arguments, wantsProgress)
        val timeoutMs = options.timeoutMs ?: config.timeoutMs

        logger.i { "Invoking tool '$toolName' on ${config.displayName}..." }

        // The session the request last went out in: where a cancellation has to be sent.
        var sentIn = current
        var answered = false
        try {
            val outcome = call(request, current, timeoutMs, onSession = { sentIn = it }) { message ->
                val event = mcpClient.callEventFor(request, message)
                if (event != null) emit(event) else logUnrouted(request, message)
            }.mapCatching { response -> mcpClient.processToolCallResponse(response).getOrThrow() }
            answered = true

            outcome.onSuccess { result ->
                logger.i {
                    "Tool '$toolName' execution completed " +
                        "(${result.content.size} content items, error=${result.isError})"
                }
            }.onFailure { error ->
                logger.e(error) { "Tool '$toolName' execution failed" }
            }
            if (outcome.exceptionOrNull() is McpRequestTimeoutException) {
                // The limit ran out with the server still working: stop waiting, and say so.
                tellServerCancelled(request, sentIn, "no answer within $timeoutMs ms")
            }
            emit(terminalEventOf(outcome))
        } catch (e: CancellationException) {
            if (!answered) {
                withContext(NonCancellable) { tellServerCancelled(request, sentIn, "the caller cancelled the call") }
            }
            throw e
        }
    }

    /**
     * Ends the session, if the server issued one, and closes the HTTP client.
     *
     * Ending the session is a courtesy the server may decline (405) and that a
     * dead server cannot answer, so its outcome never fails the disconnect.
     */
    override suspend fun disconnect(): Result<Unit> = lifecycle.withLock {
        if (!connected) {
            return Result.success(Unit)
        }

        logger.i { "Disconnecting from ${config.displayName}..." }

        session?.let { ending -> endSession(ending) }
        session = null
        connected = false
        mcpClient.reset()

        httpHandler.close().onSuccess {
            logger.i { "Successfully disconnected from ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Error during disconnect from ${config.displayName}" }
        }
    }

    private suspend fun currentSession(call: String): Session {
        val current = lifecycle.withLock { session }
        return checkNotNull(current) { "Must initialize() before $call" }
    }

    /**
     * Send [request] in [current], and once more in a new session if the server
     * says [current] has ended.
     */
    private suspend fun call(
        request: McpRequest,
        current: Session,
        timeoutMs: Long,
        onSession: (Session) -> Unit = {},
        onMessage: suspend (McpServerMessage) -> Unit,
    ): Result<McpResponse> {
        val first = roundTrip(request, current, timeoutMs, onMessage)
        if (first.exceptionOrNull() !is SessionEnded) return first.map { it.response }

        logger.i { "${config.displayName} ended the session; opening a new one" }

        val renewed = lifecycle.withLock {
            val latest = session
            when {
                // disconnect() ran while the request was out. There is no session to replace.
                latest == null -> return Result.failure(
                    McpSessionExpiredException(
                        "${config.displayName} ended the session, and the connection was closed",
                    ),
                )
                // Another request's 404 already replaced it; use theirs.
                latest.generation != current.generation -> latest
                else -> handshake(replacing = latest)
                    .map { (opened, _) -> opened.also { session = it } }
                    .getOrElse { failure ->
                        if (failure is McpUnauthorizedException) return Result.failure(failure)
                        return Result.failure(
                            McpSessionExpiredException(
                                "${config.displayName} ended the session, and a new one could not be opened: " +
                                    failure.message,
                                failure,
                            ),
                        )
                    }
            }
        }

        onSession(renewed)
        val second = roundTrip(request, renewed, timeoutMs, onMessage)
        if (second.exceptionOrNull() !is SessionEnded) return second.map { it.response }

        return Result.failure(
            McpSessionExpiredException(
                "${config.displayName} ended the session again as soon as a new one was opened",
            ),
        )
    }

    /**
     * Run the handshake and return the session it opened. Must be called
     * holding [lifecycle]; does not touch [session], so a failed attempt leaves
     * the previous one in place and the next request tries again.
     */
    private suspend fun handshake(replacing: Session?): Result<Pair<Session, InitializeResult>> {
        // `initialize` opens a session, so it never carries the id of one.
        val initialize = mcpClient.createInitializeRequest()
        val answered = roundTrip(initialize, within = null, config.timeoutMs) { logUnrouted(initialize, it) }
            .getOrElse { return Result.failure(it) }
        val result = mcpClient.processInitializeResponse(answered.response)
            .getOrElse { return Result.failure(it) }

        val sessionId = answered.sessionId
        if (sessionId != null && !sessionId.all { it in VISIBLE_ASCII }) {
            return Result.failure(
                McpProtocolException(
                    "${config.displayName} issued a session id that is not visible ASCII, so it cannot be sent back",
                ),
            )
        }

        val opened = Session(
            id = sessionId,
            protocolVersion = result.protocolVersion,
            generation = (replacing?.generation ?: 0) + 1,
        )

        notifyInitialized(opened).getOrElse { return Result.failure(it) }
        return Result.success(opened to result)
    }

    /**
     * Tell the server the handshake is complete. It answers 202 with no body.
     *
     * A refusal on grounds other than the credential is logged and not treated
     * as a failed handshake: the server has already answered `initialize`, and
     * one that minds will refuse the next request in its own words.
     */
    private suspend fun notifyInitialized(opened: Session): Result<Unit> {
        val body = mcpClient.serializeNotification(mcpClient.createInitializedNotification())

        return httpHandler.exchange<Unit>(post(body, opened, config.timeoutMs)) { response ->
            if (response.status == HTTP_UNAUTHORIZED) {
                throw httpStatusFailure(INITIALIZED, response.status, response.header(WWW_AUTHENTICATE))
            }
            if (response.status !in HTTP_SUCCESS) {
                logger.w { "${config.displayName} answered $INITIALIZED with ${response.status}; continuing" }
            }
        }
    }

    /**
     * `POST` [request] and read its response, whichever shape the server answers
     * in. [onMessage] is given every other message the server sends on the way,
     * in the caller's own coroutine.
     */
    private suspend fun roundTrip(
        request: McpRequest,
        within: Session?,
        timeoutMs: Long,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): Result<Answer> =
        httpHandler.exchange(post(mcpClient.serializeRequest(request), within, timeoutMs)) { response ->
            when {
                response.status == HTTP_NOT_FOUND && within?.id != null -> throw SessionEnded()

                response.status !in HTTP_SUCCESS ->
                    throw httpStatusFailure(request.method, response.status, response.header(WWW_AUTHENTICATE))
            }

            val answer = when (response.mediaType) {
                MEDIA_TYPE_JSON -> readJson(request, response, onMessage)
                MEDIA_TYPE_EVENT_STREAM -> readEventStream(request, response.body, onMessage)
                else -> throw McpConnectionException(
                    "${config.displayName} answered ${request.method} with status ${response.status} and " +
                        "content type '${response.mediaType ?: "none"}'; a response is $MEDIA_TYPE_JSON or " +
                        MEDIA_TYPE_EVENT_STREAM,
                )
            }
            Answer(answer, sessionId = response.header(SESSION_ID_HEADER))
        }

    private suspend fun readJson(
        request: McpRequest,
        response: McpHttpResponse,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): McpResponse {
        val messages = mcpClient.parseServerMessages(response.body.readText()).getOrThrow()
        return pickResponse(request, messages, onMessage)
            ?: throw McpProtocolException(
                "${config.displayName} answered ${request.method} with JSON that holds no response to it",
            )
    }

    private suspend fun readEventStream(
        request: McpRequest,
        body: McpHttpBody,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): McpResponse {
        val parser = SseParser()
        while (true) {
            val line = body.readLine()
            val event = if (line == null) parser.finish() else parser.feed(line)
            if (event != null) {
                responseIn(request, event, onMessage)?.let { return it }
            }
            if (line == null) break
        }
        throw McpConnectionException(
            "${config.displayName} closed the event stream for ${request.method} before answering it",
        )
    }

    /** The response to [request] carried by [event], if it carries one. Everything else in it is handed on. */
    private suspend fun responseIn(
        request: McpRequest,
        event: SseEvent,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): McpResponse? {
        // MCP messages travel as `message` events. An event of another type, or one with no data
        // (a server may send an id on its own), carries no message.
        if (event.type != SseParser.DEFAULT_EVENT_TYPE || event.data.isBlank()) return null

        val messages = mcpClient.parseServerMessages(event.data).getOrElse { malformed ->
            logger.w { "${config.displayName} sent an event that is not a JSON-RPC message: ${malformed.message}" }
            return null
        }
        return pickResponse(request, messages, onMessage)
    }

    /**
     * The response to [request] among [messages], or null when it is not there.
     * Every other message goes to [onMessage].
     *
     * An error the server could not tie to a request has no id. Answering a
     * single request with one can only be about that request, so it fails it.
     */
    private suspend fun pickResponse(
        request: McpRequest,
        messages: List<McpServerMessage>,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): McpResponse? {
        var picked: McpResponse? = null
        for (message in messages) {
            when {
                message is McpServerMessage.Response && message.id == request.id ->
                    picked = mcpClient.decodeResponse(message).getOrThrow()

                message is McpServerMessage.Response && message.id == null && "error" in message.payload ->
                    throw McpProtocolException(
                        "${config.displayName} refused ${request.method}: ${message.payload["error"]}",
                    )

                else -> onMessage(message)
            }
        }
        return picked
    }

    /**
     * A message that arrived while waiting for the response to [request] and
     * that nobody is listening for: a request of the server's own, progress for
     * another call, a notification during the handshake.
     */
    private fun logUnrouted(request: McpRequest, message: McpServerMessage) {
        logger.d {
            val what = when (message) {
                is McpServerMessage.Notification -> "notification '${message.method}'"
                is McpServerMessage.Request -> "request '${message.method}'"
                is McpServerMessage.Response -> "response to another request"
            }
            "${config.displayName} sent a $what while answering ${request.method}; nothing is listening for it"
        }
    }

    private suspend fun endSession(ending: Session) {
        if (ending.id == null) return

        val request = McpHttpRequest(
            method = McpHttpMethod.DELETE,
            url = config.endpoint,
            headers = headers(ending, accept = null),
            timeoutMs = minOf(config.timeoutMs, END_SESSION_TIMEOUT_MS),
        )
        httpHandler.exchange(request) { response -> response.status }
            .onSuccess { status ->
                logger.d {
                    when (status) {
                        in HTTP_SUCCESS -> "${config.displayName} ended ${ending.describeSession()}"
                        HTTP_METHOD_NOT_ALLOWED -> "${config.displayName} does not let a client end its session"
                        else -> "${config.displayName} answered $status when asked to end the session"
                    }
                }
            }
            .onFailure { error ->
                logger.d { "Could not ask ${config.displayName} to end the session: ${error.message}" }
            }
    }

    /**
     * Tell the server the answer to [request] is no longer wanted. Best effort,
     * and bounded: the notification gets [CANCEL_GRACE_MS] to be delivered and
     * its outcome changes nothing for the caller.
     */
    private suspend fun tellServerCancelled(request: McpRequest, within: Session, reason: String) {
        val body = mcpClient.serializeNotification(mcpClient.createCancelledNotification(request, reason))

        httpHandler.exchange(post(body, within, CANCEL_GRACE_MS)) { response -> response.status }
            .onSuccess { status ->
                logger.d { "Told ${config.displayName} that ${request.method} was cancelled ($reason): $status" }
            }
            .onFailure { error ->
                logger.d {
                    "Could not tell ${config.displayName} that ${request.method} was cancelled: ${error.message}"
                }
            }
    }

    private fun post(body: String, within: Session?, timeoutMs: Long): McpHttpRequest = McpHttpRequest(
        method = McpHttpMethod.POST,
        url = config.endpoint,
        headers = headers(within, accept = ACCEPT_JSON_OR_EVENT_STREAM),
        body = body,
        timeoutMs = timeoutMs,
    )

    private fun headers(within: Session?, accept: String?): Map<String, String> = buildMap {
        accept?.let { put(ACCEPT, it) }
        config.authToken?.let { put(AUTHORIZATION, "Bearer $it") }
        within?.id?.let { put(SESSION_ID_HEADER, it) }
        within?.protocolVersion
            ?.takeIf(McpProtocolVersion::requiresHttpVersionHeader)
            ?.let { put(PROTOCOL_VERSION_HEADER, it) }
    }

    /**
     * What a handshake agreed on.
     *
     * @property id The server's session id, or null for a server that keeps no session.
     * @property protocolVersion The revision both sides speak.
     * @property generation Counts handshakes on this connection, so two requests
     *   that both learn their session ended replace it once between them.
     */
    private class Session(
        val id: String?,
        val protocolVersion: String,
        val generation: Int,
    ) {
        /** Enough of the id to tell two sessions apart in a log, and no more. */
        fun describeSession(): String = when {
            id == null -> "no session"
            id.length > SESSION_ID_LOG_PREFIX * 2 -> "session ${id.take(SESSION_ID_LOG_PREFIX)}… (${id.length} chars)"
            else -> "session (${id.length} chars)"
        }
    }

    private class Answer(val response: McpResponse, val sessionId: String?)

    /** The server answered 404 to a request that named a session: the session is over. */
    private class SessionEnded : McpConnectionException("The MCP server no longer knows this session")

    companion object {

        /** The session header. The specification has spelled it two ways; header names ignore case. */
        const val SESSION_ID_HEADER: String = "Mcp-Session-Id"

        const val PROTOCOL_VERSION_HEADER: String = "MCP-Protocol-Version"

        /** What every `POST` declares it can read: either shape a server may answer in. */
        const val ACCEPT_JSON_OR_EVENT_STREAM: String = "application/json, text/event-stream"

        private const val ACCEPT = "Accept"
        private const val AUTHORIZATION = "Authorization"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"

        private const val MEDIA_TYPE_JSON = "application/json"
        private const val MEDIA_TYPE_EVENT_STREAM = "text/event-stream"

        private const val INITIALIZED = "notifications/initialized"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private val HTTP_SUCCESS = 200..299

        /** A session id may only contain these, by the transport's rules. */
        private val VISIBLE_ASCII = '!'..'~'

        private const val SESSION_ID_LOG_PREFIX = 4

        /** Ending a session is a courtesy: it does not get to hold a disconnect for the full request timeout. */
        private const val END_SESSION_TIMEOUT_MS = 5_000L

        /**
         * How long a cancellation waits for the server to take its `notifications/cancelled`.
         * Long enough for a live server, short enough that a dead one does not hold the caller.
         */
        const val CANCEL_GRACE_MS: Long = 2_000L
    }
}

/**
 * The platform's HTTP engine, as an [McpHttpTransport].
 *
 * This is an expect/actual class because the engine differs per platform:
 * OkHttp on the JVM and Android, the Darwin engine on iOS, `fetch` on JS and
 * wasmJs. The wire behaviour does not differ: every actual is a
 * [KtorMcpHttpTransport] over its engine, configured so the engine never times
 * a response out by itself and a response body is delivered as a stream.
 */
expect class HttpClientHandler() : McpHttpTransport {

    override suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T>

    override suspend fun close(): Result<Unit>
}
