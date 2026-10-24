package link.socket.ampere.agents.execution.executor.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * An ACP agent with a script instead of a model (AMPR-490).
 *
 * Answers `initialize` and `session/new` itself and runs [script] once per `session/prompt`,
 * on its own coroutine so the agent keeps reading while the script waits on the client. A
 * [ScriptedTurn] is what a script sees: it can stream updates, ask permission, read and write
 * through the client, wait for a cancel, or exit the process. Every answer the client gave
 * is recorded, so a test can assert what the fence decided from the agent's side.
 */
class ScriptedAcpAgent(
    private val transport: InMemoryAcpTransport,
    private val agentInfo: Implementation = Implementation(name = "scripted-acp", version = "0.1"),
    private val protocolVersion: Int = ACP_PROTOCOL_VERSION,
    private val script: suspend ScriptedTurn.() -> StopReason,
) {

    val initializations: MutableList<InitializeParams> = mutableListOf()
    val sessions: MutableList<NewSessionParams> = mutableListOf()
    val sessionIds: MutableList<String> = mutableListOf()
    val prompts: MutableList<PromptParams> = mutableListOf()
    val cancels: MutableList<String> = mutableListOf()
    val permissionAnswers: MutableList<RequestPermissionResult> = mutableListOf()
    val writeAnswers: MutableList<Result<Unit>> = mutableListOf()
    val readAnswers: MutableList<Result<String>> = mutableListOf()

    private val pending = mutableMapOf<String, CompletableDeferred<JsonRpcMessage>>()
    private val cancelSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var nextRequestId = 1000L
    private lateinit var scope: CoroutineScope

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch { loop() }
    }

    /** The text of the one prompt the agent was sent, or of the latest. */
    fun lastPromptText(): String = prompts.last().prompt.mapNotNull { it.text }.joinToString("\n")

    private suspend fun loop() {
        while (true) {
            val line = transport.agentRead() ?: break
            val message = AcpJson.decodeFromString(JsonRpcMessage.serializer(), line)
            when {
                message.isResponse -> pending.remove(message.id.toString())?.complete(message)
                message.method == AcpMethods.INITIALIZE -> {
                    initializations += decode(InitializeParams.serializer(), message.params)
                    respond(
                        message.id!!,
                        InitializeResult.serializer(),
                        InitializeResult(protocolVersion = protocolVersion, agentInfo = agentInfo),
                    )
                }
                message.method == AcpMethods.SESSION_NEW -> {
                    sessions += decode(NewSessionParams.serializer(), message.params)
                    val sessionId = "session-${sessions.size}"
                    sessionIds += sessionId
                    respond(message.id!!, NewSessionResult.serializer(), NewSessionResult(sessionId))
                }
                message.method == AcpMethods.SESSION_PROMPT -> {
                    val params = decode(PromptParams.serializer(), message.params)
                    prompts += params
                    scope.launch { runTurn(message.id!!, params) }
                }
                message.method == AcpMethods.SESSION_CANCEL -> {
                    val params = decode(CancelParams.serializer(), message.params)
                    cancels += params.sessionId
                    cancelSignals.getOrPut(params.sessionId) { CompletableDeferred() }.complete(Unit)
                }
            }
        }
    }

    private suspend fun runTurn(id: JsonElement, params: PromptParams) {
        val turn = ScriptedTurn(params)
        val stop = turn.script()
        if (turn.exited) return
        respond(id, PromptResult.serializer(), PromptResult(stop.wire))
    }

    private suspend fun <R> respond(id: JsonElement, serializer: SerializationStrategy<R>, result: R) {
        transport.agentWrite(
            AcpJson.encodeToString(
                JsonRpcMessage.serializer(),
                JsonRpcMessage.result(id, AcpJson.encodeToJsonElement(serializer, result)),
            ),
        )
    }

    private fun <P> decode(serializer: DeserializationStrategy<P>, params: JsonElement?): P =
        AcpJson.decodeFromJsonElement(serializer, params ?: error("params missing"))

    /** Sends [method] to the client and waits for its answer; an error response is a failure. */
    suspend fun request(method: String, params: JsonElement?): Result<JsonElement?> {
        val id = JsonPrimitive(nextRequestId++)
        val deferred = CompletableDeferred<JsonRpcMessage>()
        pending[id.toString()] = deferred
        transport.agentWrite(
            AcpJson.encodeToString(JsonRpcMessage.serializer(), JsonRpcMessage.request(id, method, params)),
        )
        val response = deferred.await()
        response.error?.let { return Result.failure(AcpRemoteException(method, it)) }
        return Result.success(response.result)
    }

    /** One prompt turn, as the script drives it. */
    inner class ScriptedTurn(val params: PromptParams) {

        val sessionId: String get() = params.sessionId

        val promptText: String get() = params.prompt.mapNotNull { it.text }.joinToString("\n")

        var exited: Boolean = false
            private set

        suspend fun say(text: String) = update(SessionUpdate.AgentMessageChunk(content = ContentBlock.text(text)))

        suspend fun update(update: SessionUpdate) {
            transport.agentWrite(
                AcpJson.encodeToString(
                    JsonRpcMessage.serializer(),
                    JsonRpcMessage.notification(
                        AcpMethods.SESSION_UPDATE,
                        AcpJson.encodeToJsonElement(
                            SessionNotificationParams.serializer(),
                            SessionNotificationParams(sessionId, update),
                        ),
                    ),
                ),
            )
        }

        suspend fun askPermission(
            toolCall: PermissionToolCall,
            options: List<PermissionOption> = STANDARD_OPTIONS,
        ): RequestPermissionResult {
            val result = request(
                AcpMethods.SESSION_REQUEST_PERMISSION,
                AcpJson.encodeToJsonElement(
                    RequestPermissionParams.serializer(),
                    RequestPermissionParams(sessionId, toolCall, options),
                ),
            ).getOrThrow()
            return AcpJson.decodeFromJsonElement(RequestPermissionResult.serializer(), result!!)
                .also { permissionAnswers += it }
        }

        suspend fun writeFile(path: String, content: String): Result<Unit> =
            request(
                AcpMethods.FS_WRITE_TEXT_FILE,
                AcpJson.encodeToJsonElement(
                    WriteTextFileParams.serializer(),
                    WriteTextFileParams(sessionId, path, content),
                ),
            ).map { }.also { writeAnswers += it }

        suspend fun readFile(path: String, line: Int? = null, limit: Int? = null): Result<String> =
            request(
                AcpMethods.FS_READ_TEXT_FILE,
                AcpJson.encodeToJsonElement(
                    ReadTextFileParams.serializer(),
                    ReadTextFileParams(sessionId, path, line, limit),
                ),
            ).map { AcpJson.decodeFromJsonElement(ReadTextFileResult.serializer(), it!!).content }
                .also { readAnswers += it }

        /** Any request at all to the client, for a method this client may not serve. */
        suspend fun rawRequest(method: String, params: JsonElement?): Result<JsonElement?> =
            this@ScriptedAcpAgent.request(method, params)

        /** Suspends until the client sends `session/cancel` for this session. */
        suspend fun awaitCancel() {
            cancelSignals.getOrPut(sessionId) { CompletableDeferred() }.await()
        }

        /** The process dies here: the stream closes and no prompt response follows. */
        fun exit() {
            exited = true
            transport.agentExit()
        }
    }

    companion object {
        val STANDARD_OPTIONS: List<PermissionOption> = listOf(
            PermissionOption(optionId = "allow", name = "Allow once", kind = PermissionOptionKind.ALLOW_ONCE),
            PermissionOption(optionId = "always", name = "Always allow", kind = PermissionOptionKind.ALLOW_ALWAYS),
            PermissionOption(optionId = "reject", name = "Reject", kind = PermissionOptionKind.REJECT_ONCE),
        )
    }
}
