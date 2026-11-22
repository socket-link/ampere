package link.socket.ampere.agents.tools.mcp.connection

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpClient
import link.socket.ampere.agents.tools.mcp.protocol.McpRequest
import link.socket.ampere.agents.tools.mcp.protocol.McpResponse
import link.socket.ampere.agents.tools.mcp.protocol.McpServerMessage
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.util.ioDispatcher

/**
 * StdioMcpConnection - MCP connection using Standard Input/Output transport.
 *
 * This implementation communicates with a local MCP server process via stdin/stdout.
 * The server is spawned as a child process when connect() is called, and messages
 * are exchanged via:
 * - Requests written to the process's stdin
 * - Responses read from the process's stdout
 * - Errors/logs read from the process's stderr
 *
 * The protocol follows JSON-RPC 2.0 with newline-delimited messages:
 * - Each message is a single JSON object on one line
 * - Messages are separated by newlines
 *
 * ### One stream for everything (AMPR-515)
 *
 * Stdio has no per-request stream. A response, a progress notification about
 * the call in flight, a log message and a request of the server's own all
 * arrive as lines on the same stdout, in whatever order the server writes them.
 * So a line is never assumed to be the answer:
 *
 * - One coroutine reads stdout for the life of the connection and queues every
 *   line. A caller waits on the queue, not on the process, which is what lets a
 *   call be cancelled or timed out while the server is silent: a read blocked
 *   on a pipe cannot be interrupted, a wait on a queue can.
 * - Each line is sorted, and routed by what it names. A response is taken only
 *   by the request whose id it carries; one for another id is the late answer
 *   to a call that was cancelled or timed out, and is dropped. Progress is
 *   taken only by the call whose token it carries. A log message goes to the
 *   call in flight, since nothing on the wire says which call it is about.
 *
 * Requests are sent one at a time: the next waits until the one in flight has
 * ended. A tool call follows the same rules as over HTTP (see
 * [McpServerConnection.invokeToolStreaming]): a time limit of the caller's
 * choosing, [McpServerConfiguration.timeoutMs] by default, and a
 * `notifications/cancelled` written to the server when the caller is cancelled
 * or the limit runs out, with [CANCEL_GRACE_MS] for the write to go through.
 *
 * Platform-specific implementation: The actual process spawning and I/O handling
 * is delegated to platform-specific code via StdioProcessHandler.
 */
class StdioMcpConnection internal constructor(
    private val config: McpServerConfiguration,
    private val pipe: StdioPipe,
    private val logger: Logger,
    private val readerDispatcher: CoroutineDispatcher,
) : McpServerConnection {

    constructor(
        config: McpServerConfiguration,
        processHandler: StdioProcessHandler,
        logger: Logger = Logger.withTag("StdioMcpConnection"),
    ) : this(config, ProcessPipe(processHandler), logger, ioDispatcher)

    override val serverId: String = config.id

    private val mcpClient = McpClient()

    /** Guards the process and its reader: connect and disconnect. */
    private val lifecycle = Mutex()

    /** Held by the one request in flight. The pipe is a single stream, so requests take turns. */
    private val requests = Mutex()

    /** Every line the server has written and nobody has read yet. Null while disconnected. */
    private var incoming: Channel<String>? = null

    /** Owns the coroutine that reads stdout, and the writes a cancelled caller cannot wait for. */
    private var background: CoroutineScope? = null

    override var isConnected: Boolean = false
        private set

    /**
     * Spawns the MCP server process and starts reading its stdout.
     */
    override suspend fun connect(): Result<Unit> = lifecycle.withLock {
        if (isConnected) {
            return Result.success(Unit)
        }

        logger.i { "Connecting to stdio MCP server: ${config.displayName} at ${config.endpoint}" }

        pipe.start(config.endpoint).onSuccess {
            val lines = Channel<String>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + readerDispatcher)
            scope.launch { readInto(lines) }

            incoming = lines
            background = scope
            isConnected = true
            logger.i { "Successfully connected to ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Failed to connect to ${config.displayName}" }
        }
    }

    /**
     * Performs the MCP initialize handshake via stdin/stdout, and completes it
     * with `notifications/initialized`.
     */
    override suspend fun initialize(): Result<InitializeResult> = requests.withLock {
        check(isConnected) { "Must connect() before initialize()" }

        logger.i { "Initializing MCP handshake with ${config.displayName}..." }

        val request = mcpClient.createInitializeRequest()
        val result = roundTrip(request, config.timeoutMs) { message -> logUnrouted(request, message) }
            .mapCatching { response -> mcpClient.processInitializeResponse(response).getOrThrow() }
            .getOrElse { error ->
                logger.e(error) { "MCP handshake failed with ${config.displayName}" }
                return Result.failure(error)
            }

        pipe.send(mcpClient.serializeNotification(mcpClient.createInitializedNotification()))
            .getOrElse { error ->
                logger.e(error) { "MCP handshake with ${config.displayName} could not be completed" }
                return Result.failure(error)
            }

        logger.i {
            "MCP handshake successful with ${config.displayName}: " +
                "${result.serverInfo.name} v${result.serverInfo.version}"
        }
        Result.success(result)
    }

    /**
     * Queries available tools via stdio.
     */
    override suspend fun listTools(): Result<List<McpToolDescriptor>> = requests.withLock {
        check(mcpClient.isInitialized) { "Must initialize() before listTools()" }

        logger.i { "Listing tools from ${config.displayName}..." }

        val request = mcpClient.createToolsListRequest()
        roundTrip(request, config.timeoutMs) { message -> logUnrouted(request, message) }
            .mapCatching { response -> mcpClient.processToolsListResponse(response).getOrThrow() }
            .map { result ->
                logger.i { "Discovered ${result.tools.size} tools from ${config.displayName}" }
                result.tools
            }.onFailure { error ->
                logger.e(error) { "Failed to list tools from ${config.displayName}" }
            }
    }

    /**
     * Invokes a tool via stdio and returns how it ended. No progress is asked for.
     */
    override suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult> =
        toolCall(toolName, arguments, McpCallOptions.Default, wantsProgress = false).awaitResult()

    /**
     * Invokes a tool via stdio, asking the server for progress, and yields what
     * it reports until the one terminal event.
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
        check(mcpClient.isInitialized) { "Must initialize() before invokeTool()" }
        val timeoutMs = options.timeoutMs ?: config.timeoutMs

        logger.i { "Invoking tool '$toolName' on ${config.displayName}..." }

        val outcome = requests.withLock {
            val request = mcpClient.createToolCallRequest(toolName, arguments, wantsProgress)
            var answered = false
            try {
                val result = roundTrip(request, timeoutMs) { message ->
                    val event = mcpClient.callEventFor(request, message)
                    if (event != null) emit(event) else logUnrouted(request, message)
                }.mapCatching { response -> mcpClient.processToolCallResponse(response).getOrThrow() }
                answered = true

                if (result.exceptionOrNull() is McpRequestTimeoutException) {
                    // The limit ran out with the server still working: stop waiting, and say so.
                    tellServerCancelled(request, "no answer within $timeoutMs ms")
                }
                result
            } catch (e: CancellationException) {
                if (!answered) {
                    withContext(NonCancellable) { tellServerCancelled(request, "the caller cancelled the call") }
                }
                throw e
            }
        }

        outcome.onSuccess { result ->
            logger.i {
                "Tool '$toolName' execution completed " +
                    "(${result.content.size} content items, error=${result.isError})"
            }
        }.onFailure { error ->
            logger.e(error) { "Tool '$toolName' execution failed" }
        }
        // Outside the lock: a slow collector does not hold up the next request.
        emit(terminalEventOf(outcome))
    }

    /**
     * Terminates the child process and cleans up streams. A request in flight
     * fails with the stream's end rather than being waited for.
     */
    override suspend fun disconnect(): Result<Unit> = lifecycle.withLock {
        if (!isConnected) {
            return Result.success(Unit)
        }

        logger.i { "Disconnecting from ${config.displayName}..." }

        pipe.stop().onSuccess {
            background?.cancel()
            background = null
            incoming = null
            isConnected = false
            mcpClient.reset()
            logger.i { "Successfully disconnected from ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Error during disconnect from ${config.displayName}" }
        }
    }

    /** Queue every line the server writes until the stream ends, then say why it ended. */
    private suspend fun readInto(lines: Channel<String>) {
        try {
            while (true) {
                lines.send(pipe.receive())
            }
        } catch (e: CancellationException) {
            lines.close()
            throw e
        } catch (e: Exception) {
            // The process exited or its stdout broke. Whoever is waiting learns why.
            lines.close(e)
        }
    }

    /**
     * Write [request] and wait for the response that carries its id, for at
     * most [timeoutMs]. Every other message read on the way goes to
     * [onMessage], in the caller's own coroutine. Must be called holding
     * [requests].
     */
    private suspend fun roundTrip(
        request: McpRequest,
        timeoutMs: Long,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): Result<McpResponse> {
        val lines = incoming
            ?: return Result.failure(McpConnectionException("${config.displayName} is not connected"))

        pipe.send(mcpClient.serializeRequest(request)).getOrElse { return Result.failure(it) }

        return withTimeoutOrNull(timeoutMs) { awaitResponse(request, lines, onMessage) }
            ?: Result.failure(McpRequestTimeoutException(timeoutMs))
    }

    private suspend fun awaitResponse(
        request: McpRequest,
        lines: Channel<String>,
        onMessage: suspend (McpServerMessage) -> Unit,
    ): Result<McpResponse> {
        while (true) {
            val received = lines.receiveCatching()
            val line = received.getOrNull()
                ?: return Result.failure(
                    McpConnectionException(
                        "${config.displayName} closed its output before answering ${request.method}",
                        received.exceptionOrNull(),
                    ),
                )

            val messages = mcpClient.parseServerMessages(line).getOrNull()
            if (messages == null) {
                // A server must write only MCP messages to stdout; one that does not is not obeyed, and
                // the line is not quoted: nothing says what it holds.
                logger.w { "${config.displayName} wrote a line that is not a JSON-RPC message; skipped" }
            } else {
                for (message in messages) {
                    if (message !is McpServerMessage.Response) {
                        onMessage(message)
                        continue
                    }
                    if (message.id == request.id) return mcpClient.decodeResponse(message)
                    logger.d { "${config.displayName} answered a request that is no longer waiting; dropped" }
                }
            }
        }
    }

    /**
     * Write the `notifications/cancelled` for [request]. Best effort, and
     * bounded: a write to a process that has stopped reading can block for
     * ever, so it runs on its own and is waited for at most [CANCEL_GRACE_MS].
     */
    private suspend fun tellServerCancelled(request: McpRequest, reason: String) {
        val scope = background ?: return
        val notification = mcpClient.serializeNotification(mcpClient.createCancelledNotification(request, reason))

        val writing = scope.launch { pipe.send(notification) }
        val written = withTimeoutOrNull(CANCEL_GRACE_MS) { writing.join() } != null
        logger.d {
            if (written) {
                "Told ${config.displayName} that ${request.method} was cancelled ($reason)"
            } else {
                "${config.displayName} did not take the cancellation of ${request.method} within $CANCEL_GRACE_MS ms"
            }
        }
    }

    /** A message that arrived while waiting for the response to [request] and that nobody is listening for. */
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

    companion object {

        /**
         * How long a cancellation waits for its `notifications/cancelled` to be written.
         * Long enough for a live server, short enough that a stuck one does not hold the caller.
         */
        const val CANCEL_GRACE_MS: Long = 2_000L
    }
}

/**
 * The four things a stdio connection needs from a child process. The seam the
 * connection is tested through; production is [StdioProcessHandler].
 */
internal interface StdioPipe {

    suspend fun start(executablePath: String): Result<Unit>

    /** Write one message as a line. */
    suspend fun send(message: String): Result<Unit>

    /** The next line the process wrote. Waits for one; throws when the stream has ended. */
    suspend fun receive(): String

    suspend fun stop(): Result<Unit>
}

private class ProcessPipe(private val handler: StdioProcessHandler) : StdioPipe {
    override suspend fun start(executablePath: String): Result<Unit> = handler.startProcess(executablePath)
    override suspend fun send(message: String): Result<Unit> = handler.sendMessage(message)
    override suspend fun receive(): String = handler.receiveMessage()
    override suspend fun stop(): Result<Unit> = handler.stopProcess()
}

/**
 * Platform-specific handler for stdio process management.
 *
 * This is an expect/actual interface - implementations differ per platform:
 * - JVM: Uses ProcessBuilder and Process I/O streams
 * - JS/Node: Uses child_process module
 * - Native: Uses platform-specific process APIs
 */
expect class StdioProcessHandler() {
    /**
     * Starts the MCP server process.
     *
     * @param executablePath Path to the executable
     * @return Result indicating success or failure
     */
    suspend fun startProcess(executablePath: String): Result<Unit>

    /**
     * Sends a message to the process via stdin.
     *
     * @param message The JSON message (single line, no trailing newline)
     * @return Result indicating success or failure
     */
    suspend fun sendMessage(message: String): Result<Unit>

    /**
     * Receives a message from the process via stdout.
     *
     * Blocks until a complete message is received (newline-delimited).
     *
     * @return The received JSON message
     * @throws Exception if read fails or process terminates
     */
    suspend fun receiveMessage(): String

    /**
     * Stops the process and cleans up resources.
     *
     * @return Result indicating success or failure
     */
    suspend fun stopProcess(): Result<Unit>
}
