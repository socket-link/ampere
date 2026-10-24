package link.socket.ampere.agents.execution.executor.acp

import co.touchlab.kermit.Logger
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The connection failed on this side: the stream closed, a result was missing or malformed. */
class AcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The agent answered a request with a JSON-RPC error. */
class AcpRemoteException(
    val method: String,
    val error: JsonRpcError,
) : Exception("ACP $method failed: ${error.code} ${error.message}")

/**
 * Thrown by an [AcpAgentRequestHandler] to answer the agent's request with a JSON-RPC error
 * of [code] rather than a result. Any other exception from a handler is reported to the
 * agent as [JsonRpcError.INTERNAL_ERROR].
 */
class AcpRequestRefused(
    val code: Int,
    message: String,
) : Exception(message)

/**
 * The client side of the requests an agent makes *to* the client during a turn.
 *
 * ACP is bidirectional: while a prompt is in flight the agent may ask permission for a tool
 * call and, when the client advertised the `fs` capabilities, read and write files through
 * the client. The executor implements this against its fence (AMPR-492).
 */
interface AcpAgentRequestHandler {

    suspend fun requestPermission(params: RequestPermissionParams): RequestPermissionResult

    suspend fun readTextFile(params: ReadTextFileParams): ReadTextFileResult

    suspend fun writeTextFile(params: WriteTextFileParams)
}

/**
 * One JSON-RPC connection to one ACP agent (AMPR-490).
 *
 * Owns the reader coroutine over [transport], correlates the agent's responses to the
 * requests this side sent by id, delivers `session/update` notifications to the listener
 * registered for their session, and answers the agent's own requests through [handler] —
 * each on its own coroutine on [scope], so a slow file write never stalls the reader.
 *
 * A request this side sends is a [CompletableDeferred] until its response arrives; the far
 * side closing its stream fails every outstanding one with an [AcpException], which is how
 * an agent that died mid-turn reaches the executor as a typed failure rather than a hang.
 *
 * Every method that talks to the agent is `suspend` and none blocks.
 */
class AcpConnection(
    private val transport: AcpTransport,
    private val handler: AcpAgentRequestHandler,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.withTag("AcpConnection"),
) {

    private val pendingLock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonRpcMessage>>()
    private var nextId = 0L

    private val sendLock = Mutex()

    private val listenersLock = Mutex()
    private val updateListeners = mutableMapOf<String, suspend (SessionUpdate) -> Unit>()

    private var reader: Job? = null
    private var closed = false

    /** Whether a request can still be sent: opened, not closed, the reader alive and the far side too. */
    val isOpen: Boolean
        get() = !closed && reader?.isActive == true && transport.isAlive

    /** Starts the transport and the reader. A failure is the transport's reason. */
    suspend fun open(): Result<Unit> =
        transport.start().onSuccess {
            reader = scope.launch { readLoop() }
        }

    /** Ends the connection: the reader, the transport, and every request still waiting. */
    suspend fun close() {
        if (closed) return
        closed = true
        reader?.cancel()
        runCatching { transport.close() }
        failPending(AcpException("the connection was closed"))
    }

    // ---- client -> agent --------------------------------------------------------------

    /**
     * Sends [method] with [params] and suspends until its result, which may be absent.
     * Throws [AcpRemoteException] for an error response, [AcpException] when the stream
     * closed first.
     */
    suspend fun request(method: String, params: JsonElement?): JsonElement? {
        val (key, id, deferred) = register()
        try {
            send(JsonRpcMessage.request(id, method, params))
            val response = deferred.await()
            response.error?.let { throw AcpRemoteException(method, it) }
            return response.result
        } finally {
            // NonCancellable: this also runs when the caller was cancelled, and a cancelled
            // coroutine may not suspend on a contended lock.
            withContext(NonCancellable) { pendingLock.withLock { pending.remove(key) } }
        }
    }

    /** Sends a notification: no id, no response. */
    suspend fun notify(method: String, params: JsonElement?) {
        send(JsonRpcMessage.notification(method, params))
    }

    suspend fun initialize(
        capabilities: ClientCapabilities,
        clientInfo: Implementation,
    ): InitializeResult = requestTyped(
        method = AcpMethods.INITIALIZE,
        params = InitializeParams(
            protocolVersion = ACP_PROTOCOL_VERSION,
            clientCapabilities = capabilities,
            clientInfo = clientInfo,
        ),
        paramsSerializer = InitializeParams.serializer(),
        resultSerializer = InitializeResult.serializer(),
    )

    suspend fun newSession(cwd: String): NewSessionResult = requestTyped(
        method = AcpMethods.SESSION_NEW,
        params = NewSessionParams(cwd = cwd),
        paramsSerializer = NewSessionParams.serializer(),
        resultSerializer = NewSessionResult.serializer(),
    )

    /**
     * One prompt turn on [sessionId]: sends [blocks], delivers every `session/update` for
     * the session to [onUpdate] while the turn runs, and returns the agent's stop reason.
     *
     * Cancellation is the ACP handshake (AMPR-489, D9): the cancelling coroutine sends
     * `session/cancel` under [NonCancellable], waits up to [cancelGrace] for the agent to
     * answer the open prompt with `cancelled`, and closes the connection — which ends the
     * agent — if it does not. Then the cancellation propagates as itself.
     */
    suspend fun prompt(
        sessionId: String,
        blocks: List<ContentBlock>,
        cancelGrace: Duration,
        onUpdate: suspend (SessionUpdate) -> Unit,
    ): PromptResult {
        listenersLock.withLock { updateListeners[sessionId] = onUpdate }
        val (key, id, deferred) = register()
        try {
            send(
                JsonRpcMessage.request(
                    id = id,
                    method = AcpMethods.SESSION_PROMPT,
                    params = AcpJson.encodeToJsonElement(PromptParams.serializer(), PromptParams(sessionId, blocks)),
                ),
            )
            val response = try {
                deferred.await()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { cancelTurn(sessionId, deferred, cancelGrace) }
                throw e
            }
            response.error?.let { throw AcpRemoteException(AcpMethods.SESSION_PROMPT, it) }
            return decodeResult(PromptResult.serializer(), response.result, AcpMethods.SESSION_PROMPT)
        } finally {
            withContext(NonCancellable) {
                pendingLock.withLock { pending.remove(key) }
                listenersLock.withLock { updateListeners.remove(sessionId) }
            }
        }
    }

    /** Asks the agent to stop the turn on [sessionId]. A notification; the prompt's response says whether it did. */
    suspend fun cancel(sessionId: String) {
        notify(
            AcpMethods.SESSION_CANCEL,
            AcpJson.encodeToJsonElement(CancelParams.serializer(), CancelParams(sessionId)),
        )
    }

    private suspend fun cancelTurn(
        sessionId: String,
        deferred: CompletableDeferred<JsonRpcMessage>,
        grace: Duration,
    ) {
        runCatching { cancel(sessionId) }
            .onFailure { logger.w(it) { "Could not send session/cancel; closing the connection" } }
        val answered = withTimeoutOrNull(grace) { runCatching { deferred.await() }.isSuccess } ?: false
        if (!answered) {
            logger.w { "The agent did not answer session/cancel within $grace; closing the connection" }
            close()
        }
    }

    private suspend fun <P, R> requestTyped(
        method: String,
        params: P,
        paramsSerializer: SerializationStrategy<P>,
        resultSerializer: DeserializationStrategy<R>,
    ): R {
        val result = request(method, AcpJson.encodeToJsonElement(paramsSerializer, params))
        return decodeResult(resultSerializer, result, method)
    }

    private fun <R> decodeResult(
        serializer: DeserializationStrategy<R>,
        result: JsonElement?,
        method: String,
    ): R {
        val element = result ?: throw AcpException("ACP $method returned no result")
        return try {
            AcpJson.decodeFromJsonElement(serializer, element)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AcpException("ACP $method returned a result this client cannot read: ${e.message}", e)
        }
    }

    private suspend fun register(): Triple<String, JsonElement, CompletableDeferred<JsonRpcMessage>> =
        pendingLock.withLock {
            val id = JsonPrimitive(++nextId)
            val key = id.toString()
            val deferred = CompletableDeferred<JsonRpcMessage>()
            pending[key] = deferred
            Triple(key, id, deferred)
        }

    private suspend fun send(message: JsonRpcMessage) {
        val line = AcpJson.encodeToString(JsonRpcMessage.serializer(), message)
        sendLock.withLock {
            try {
                transport.send(line)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AcpException("could not write to the agent: ${e.message}", e)
            }
        }
    }

    // ---- agent -> client --------------------------------------------------------------

    private suspend fun readLoop() {
        try {
            while (true) {
                val line = transport.receive() ?: break
                if (line.isBlank()) continue
                val message = try {
                    AcpJson.decodeFromString(JsonRpcMessage.serializer(), line)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(e) { "Dropping a line from the agent that is not JSON-RPC" }
                    continue
                }
                when {
                    message.isRequest -> scope.launch { answer(message) }
                    message.isNotification -> deliverNotification(message)
                    message.isResponse -> complete(message)
                    else -> logger.w { "Dropping a message that is neither request, notification nor response" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "The reader stopped on an error" }
        } finally {
            failPending(AcpException("the agent closed its stream"))
        }
    }

    private suspend fun complete(message: JsonRpcMessage) {
        val key = message.id.toString()
        val deferred = pendingLock.withLock { pending.remove(key) }
        if (deferred == null) {
            logger.w { "A response arrived for a request nobody is waiting on: $key" }
            return
        }
        deferred.complete(message)
    }

    private suspend fun deliverNotification(message: JsonRpcMessage) {
        if (message.method != AcpMethods.SESSION_UPDATE) {
            logger.d { "Ignoring notification ${message.method}" }
            return
        }
        val params = message.params ?: return
        val notification = try {
            AcpJson.decodeFromJsonElement(SessionNotificationParams.serializer(), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Dropping a session/update this client cannot read" }
            return
        }
        val listener = listenersLock.withLock { updateListeners[notification.sessionId] } ?: return
        try {
            listener(notification.update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "A session/update listener threw; the update is dropped" }
        }
    }

    private suspend fun answer(message: JsonRpcMessage) {
        val id = message.id ?: return
        val response = try {
            JsonRpcMessage.result(id, serve(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AcpRequestRefused) {
            JsonRpcMessage.error(id, JsonRpcError(e.code, e.message ?: "refused"))
        } catch (e: Exception) {
            logger.e(e) { "The handler for ${message.method} threw" }
            JsonRpcMessage.error(
                id,
                JsonRpcError(JsonRpcError.INTERNAL_ERROR, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        runCatching { send(response) }
            .onFailure { logger.w(it) { "Could not answer the agent's ${message.method}" } }
    }

    private suspend fun serve(message: JsonRpcMessage): JsonElement = when (message.method) {
        AcpMethods.SESSION_REQUEST_PERMISSION -> AcpJson.encodeToJsonElement(
            RequestPermissionResult.serializer(),
            handler.requestPermission(decodeParams(RequestPermissionParams.serializer(), message)),
        )
        AcpMethods.FS_READ_TEXT_FILE -> AcpJson.encodeToJsonElement(
            ReadTextFileResult.serializer(),
            handler.readTextFile(decodeParams(ReadTextFileParams.serializer(), message)),
        )
        AcpMethods.FS_WRITE_TEXT_FILE -> {
            handler.writeTextFile(decodeParams(WriteTextFileParams.serializer(), message))
            JsonObject(emptyMap())
        }
        else -> throw AcpRequestRefused(
            JsonRpcError.METHOD_NOT_FOUND,
            "this client does not serve ${message.method}",
        )
    }

    private fun <P> decodeParams(serializer: DeserializationStrategy<P>, message: JsonRpcMessage): P {
        val params = message.params
            ?: throw AcpRequestRefused(JsonRpcError.INVALID_PARAMS, "${message.method} needs params")
        return try {
            AcpJson.decodeFromJsonElement(serializer, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AcpRequestRefused(JsonRpcError.INVALID_PARAMS, "${message.method}: ${e.message}")
        }
    }

    private suspend fun failPending(cause: AcpException) {
        val waiting = pendingLock.withLock {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        waiting.forEach { it.completeExceptionally(cause) }
    }
}
