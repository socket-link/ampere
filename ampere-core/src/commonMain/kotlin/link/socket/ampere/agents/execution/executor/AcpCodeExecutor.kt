package link.socket.ampere.agents.execution.executor

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.AMPERE_RUNTIME_VERSION
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.ACP_PROTOCOL_VERSION
import link.socket.ampere.agents.execution.executor.acp.AcpAgentRequestHandler
import link.socket.ampere.agents.execution.executor.acp.AcpConnection
import link.socket.ampere.agents.execution.executor.acp.AcpException
import link.socket.ampere.agents.execution.executor.acp.AcpRemoteException
import link.socket.ampere.agents.execution.executor.acp.AcpRequestRefused
import link.socket.ampere.agents.execution.executor.acp.AcpTransport
import link.socket.ampere.agents.execution.executor.acp.ClientCapabilities
import link.socket.ampere.agents.execution.executor.acp.ContentBlock
import link.socket.ampere.agents.execution.executor.acp.Fingerprint
import link.socket.ampere.agents.execution.executor.acp.FsCapabilities
import link.socket.ampere.agents.execution.executor.acp.HarnessFence
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.HarnessPrompt
import link.socket.ampere.agents.execution.executor.acp.HarnessTurn
import link.socket.ampere.agents.execution.executor.acp.Implementation
import link.socket.ampere.agents.execution.executor.acp.JsonRpcError
import link.socket.ampere.agents.execution.executor.acp.PermissionOutcome
import link.socket.ampere.agents.execution.executor.acp.PermissionToolCall
import link.socket.ampere.agents.execution.executor.acp.PromptResult
import link.socket.ampere.agents.execution.executor.acp.ReadTextFileParams
import link.socket.ampere.agents.execution.executor.acp.ReadTextFileResult
import link.socket.ampere.agents.execution.executor.acp.RequestPermissionParams
import link.socket.ampere.agents.execution.executor.acp.RequestPermissionResult
import link.socket.ampere.agents.execution.executor.acp.SessionUpdate
import link.socket.ampere.agents.execution.executor.acp.StopReason
import link.socket.ampere.agents.execution.executor.acp.ToolKind
import link.socket.ampere.agents.execution.executor.acp.WorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.WriteTextFileParams
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.results.ExecutionResultCodeChanges
import link.socket.ampere.agents.execution.results.FileChangeResult
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.health.ExecutorSystemHealth
import link.socket.ampere.util.ioDispatcher
import link.socket.ampere.util.systemFileSystem
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * The first [CodeExecutor]: a plan step's code change, performed by an external coding
 * harness that speaks the Agent Client Protocol (AMPR-444, AMPR-492).
 *
 * One instance is one harness binary. Its agent process is spawned lazily through
 * [transportFactory] on the first call, kept across runs, and spawned again on the next
 * call after it exits; [close] ends it. Inside the process, one ACP session is opened per
 * `(workspace, run)`, so the steps of one run share the agent's memory of the checkout and two
 * runs on one checkout do not (D2).
 *
 * A call is one prompt turn. The step's description, the ticket, the workspace, the file
 * access scope as prose and the earlier steps' results go to the agent as one text block
 * ([HarnessPrompt]); what the agent streams back becomes non-terminal [ExecutionStatus]es
 * ([HarnessTurn]); the turn's end becomes the terminal status — an
 * [ExecutionOutcome.CodeChanged] attributed to this executor's [id]. A turn that changed
 * nothing fails when the step asked for a code change — its task is a [Task.CodeChange] or
 * its tool is `harness_code_change` — because the step has not done what the plan asked,
 * whatever the agent said about why; otherwise it is a [ExecutionOutcome.NoChanges.Success]
 * (D3, AMPR-507).
 *
 * The fence (D4) has five layers and this class is three of them. The session is rooted at
 * the workspace. Every `session/request_permission` is answered by a [HarnessFence] from the
 * request's `fileAccessScope`, `allow_once` or a rejection, never a standing grant. The
 * client-side `fs/read_text_file` and `fs/write_text_file` are advertised and served under
 * the same fence, for an agent that uses them; OpenCode does not (anomalyco/opencode#38196,
 * not called in 2.2.0: AMPR-498 saw neither method in any turn, with both advertised and
 * edits allowed), so for it that handler and `servedWrites` stay dormant. And after the turn,
 * what actually changed on disk — a [WorkspaceObserver] snapshot before against one after —
 * is checked path by path against the scope; a changed path the scope does not permit fails
 * the step, naming it, because a harness may write from its own process without asking, as
 * OpenCode always does. Nothing is reverted: that is the person's call. The
 * Breaker's admission of the step is the first layer and happens before this class is reached.
 *
 * The workspace is the request's when it names one, else [workspace], the checkout this
 * instance was bound to at construction — a hosted seat carries none (D2 amendment). With
 * neither, the step is refused before any process is touched.
 *
 * `commonMain` on purpose: `CodeExecutor` is sealed, and this is the protocol over an
 * [AcpTransport]. The transport that owns a process, and the observer that asks git, are
 * `jvmMain` (`StdioAcpTransport`, `GitWorkspaceObserver`, AMPR-493).
 *
 * @param id This harness's `Executor.id` — `opencode` for the first — recorded as the
 *   performer on the tool pair, the context and the outcome row (AMPR-443).
 * @param transportFactory One fresh transport per spawn.
 * @param observerFactory The observer for a workspace, asked before and after each turn.
 *   [WorkspaceObserver.None] by default: then the outcome's changed files are what the agent
 *   reported, and the outcome says so.
 * @param workspace The checkout this executor is bound to, used when a request names none.
 * @param fileSystem Where `fs/…` requests read and write. The system's by default.
 * @param readerScope Where the connection's reader and the agent's requests run. Null — the
 *   default — is a scope of this executor's own on the IO dispatcher, ended by [close]. A test
 *   passes its own so the whole exchange runs on one scheduler.
 */
class AcpCodeExecutor(
    override val id: ExecutorId,
    override val displayName: String,
    private val transportFactory: () -> AcpTransport,
    private val observerFactory: (ExecutionWorkspace) -> WorkspaceObserver = { WorkspaceObserver.None },
    private val policy: HarnessPolicy = HarnessPolicy(),
    private val workspace: ExecutionWorkspace? = null,
    private val fileSystem: FileSystem = systemFileSystem,
    override val capabilities: ExecutorCapabilities = ExecutorCapabilities(
        supportsLanguages = emptySet(),
        supportsFrameworks = emptySet(),
    ),
    private val clientInfo: Implementation = Implementation(
        name = "ampere",
        title = "Ampere",
        version = AMPERE_RUNTIME_VERSION,
    ),
    private val logger: Logger = Logger.withTag("AcpCodeExecutor"),
    private val readerScope: CoroutineScope? = null,
) : CodeExecutor {

    private class Live(
        val connection: AcpConnection,
        val agent: Implementation?,
        val sessions: MutableMap<String, String> = mutableMapOf(),
    )

    private class ActiveTurn(
        val fence: HarnessFence,
        val turn: HarnessTurn,
    )

    private sealed interface TurnEnd {
        data class Finished(val result: PromptResult) : TurnEnd

        data object TimedOut : TurnEnd

        data class Failed(val cause: Exception) : TurnEnd
    }

    private val scope = readerScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lifecycle = Mutex()
    private var live: Live? = null
    private var closed = false

    private val turnsLock = Mutex()
    private val turns = mutableMapOf<String, ActiveTurn>()

    /** The agent's name and version once it has answered `initialize`; null before. */
    val agentInfo: Implementation?
        get() = live?.agent

    override suspend fun performHealthCheck(): Result<ExecutorSystemHealth> =
        Result.success(
            ensureLive().fold(
                onSuccess = { live ->
                    ExecutorSystemHealth(
                        version = live.agent?.version,
                        isAvailable = true,
                        issues = emptyList(),
                    )
                },
                onFailure = { error ->
                    ExecutorSystemHealth(
                        version = null,
                        isAvailable = false,
                        issues = listOf(error.message ?: "the agent could not be started"),
                    )
                },
            ),
        )

    override suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus> = flow {
        val startedAt = Clock.System.now()
        val target = request.workspace ?: workspace
        if (target == null) {
            emit(
                failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.WORKSPACE_ERROR,
                    message = "Refused ${tool.id}: the request names no workspace and $displayName is bound to none.",
                    retryable = false,
                    partiallyChangedFiles = emptyList(),
                ),
            )
            return@flow
        }

        emit(ExecutionStatus.Started(executorId = id, timestamp = Clock.System.now()))

        val live = ensureLive().getOrElse { error ->
            emit(
                failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.TOOL_UNAVAILABLE,
                    message = "$displayName could not be started: ${error.message}",
                    retryable = true,
                    partiallyChangedFiles = emptyList(),
                ),
            )
            return@flow
        }
        val sessionId = sessionFor(live, target, request.runId).getOrElse { error ->
            emit(
                failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.TOOL_UNAVAILABLE,
                    message = "$displayName could not open a session on ${target.baseDirectory}: ${error.message}",
                    retryable = true,
                    partiallyChangedFiles = emptyList(),
                ),
            )
            return@flow
        }

        val fence = HarnessFence(target.baseDirectory, request.fileAccessScope, policy)
        val turn = HarnessTurn(id, fence)
        val observer = observerFactory(target)
        val before = observer.snapshot()

        emit(
            ExecutionStatus.Planning(
                executorId = id,
                timestamp = Clock.System.now(),
                strategy = "Delegating the step to $displayName on ${target.baseDirectory}",
            ),
        )

        val prompt = HarnessPrompt.render(request, target, request.fileAccessScope)
        val end = runTurn(live, sessionId, fence, turn, prompt)

        val after = observer.snapshot()
        // A hosted run offers the tool seat-namespaced (`doer/harness_code_change`).
        val askedForChange = request.context.task is Task.CodeChange ||
            RoutedExecutor.bareIdOf(tool.id) == HARNESS_CODE_CHANGE_TOOL_ID
        emit(settle(request, startedAt, live, fence, turn, end, before, after, askedForChange))
    }

    /** Ends the agent process, if any. No call spawns another afterwards. */
    suspend fun close() {
        lifecycle.withLock {
            closed = true
            live?.connection?.close()
            live = null
        }
        if (readerScope == null) scope.cancel()
    }

    // ---- the turn --------------------------------------------------------------------------

    /**
     * Runs one prompt turn, emitting what the agent streams as it arrives, and returns how it
     * ended. The agent's updates land on the reader coroutine; they cross to this flow's
     * coroutine through a channel, because a flow may only emit from the coroutine that
     * collects it.
     */
    private suspend fun FlowCollector<ExecutionStatus>.runTurn(
        live: Live,
        sessionId: String,
        fence: HarnessFence,
        turn: HarnessTurn,
        prompt: String,
    ): TurnEnd {
        turnsLock.withLock { turns[sessionId] = ActiveTurn(fence, turn) }
        val statuses = Channel<ExecutionStatus>(Channel.UNLIMITED)
        try {
            return coroutineScope {
                val ending = async {
                    try {
                        promptOnce(live, sessionId, prompt) { update ->
                            turn.collect(update).forEach { statuses.send(it) }
                        }
                    } finally {
                        statuses.close()
                    }
                }
                for (status in statuses) emit(status)
                ending.await()
            }
        } finally {
            withContext(NonCancellable) { turnsLock.withLock { turns.remove(sessionId) } }
        }
    }

    private suspend fun promptOnce(
        live: Live,
        sessionId: String,
        prompt: String,
        onUpdate: suspend (SessionUpdate) -> Unit,
    ): TurnEnd {
        val blocks = listOf(ContentBlock.text(prompt))
        return try {
            val timeout = policy.promptTimeout
            val result = if (timeout == null) {
                live.connection.prompt(sessionId, blocks, policy.cancelGrace, onUpdate)
            } else {
                withTimeoutOrNull(timeout) { live.connection.prompt(sessionId, blocks, policy.cancelGrace, onUpdate) }
                    ?: return TurnEnd.TimedOut
            }
            TurnEnd.Finished(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurnEnd.Failed(e)
        }
    }

    // ---- the terminal status ---------------------------------------------------------------

    private suspend fun settle(
        request: ExecutionRequest<*>,
        startedAt: Instant,
        live: Live,
        fence: HarnessFence,
        turn: HarnessTurn,
        end: TurnEnd,
        before: Result<Map<String, Fingerprint>>,
        after: Result<Map<String, Fingerprint>>,
        askedForChange: Boolean,
    ): ExecutionStatus {
        val observed = before.isSuccess && after.isSuccess
        val changed: List<String> = if (observed) {
            WorkspaceObserver.changedBetween(before.getOrThrow(), after.getOrThrow()).sorted()
        } else {
            (turn.reportedEdits.keys + turn.servedWrites).sorted()
        }
        val notes = buildList {
            if (!observed) {
                val why = (before.exceptionOrNull() ?: after.exceptionOrNull())?.message
                add("Changed files are what the harness reported; the workspace was not observed ($why).")
            }
            if (turn.refusedRequests.isNotEmpty()) {
                add("Refused ${turn.refusedRequests.size} request(s): ${turn.refusedRequests.joinToString("; ")}")
            }
        }
        val summary = listOf(turn.finalMessage).filter { it.isNotBlank() } + notes

        return when (end) {
            is TurnEnd.Finished -> when (end.result.reason) {
                StopReason.END_TURN -> settleEndTurn(request, startedAt, fence, turn, changed, summary, askedForChange)
                StopReason.MAX_TOKENS, StopReason.MAX_TURN_REQUESTS -> failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.TIMEOUT,
                    message = "$displayName stopped before finishing (${end.result.stopReason}).",
                    details = summary.joinToString("\n").ifBlank { null },
                    retryable = true,
                    partiallyChangedFiles = changed,
                )
                StopReason.REFUSAL -> failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.UNEXPECTED,
                    message = "$displayName refused the step.",
                    details = summary.joinToString("\n").ifBlank { null },
                    retryable = false,
                    partiallyChangedFiles = changed,
                )
                // An agent may end the turn rather than carry on after the fence refuses it
                // (OpenCode 2.2.0 answers `cancelled`, AMPR-505). The refusal is why the step
                // stopped, and a retry would meet it again.
                StopReason.CANCELLED -> if (turn.refusals.isNotEmpty()) {
                    failed(
                        request = request,
                        startedAt = startedAt,
                        type = if (turn.refusals.any { it.kind == HarnessTurn.RefusalKind.PATH }) {
                            ExecutionError.Type.WORKSPACE_ERROR
                        } else {
                            ExecutionError.Type.UNEXPECTED
                        },
                        message = "$displayName ended the turn after it was refused: " +
                            turn.refusedRequests.joinToString("; ") + ".",
                        details = summary.joinToString("\n").ifBlank { null },
                        retryable = false,
                        partiallyChangedFiles = changed,
                    )
                } else {
                    failed(
                        request = request,
                        startedAt = startedAt,
                        type = ExecutionError.Type.UNEXPECTED,
                        message = "$displayName cancelled the turn on its own.",
                        details = summary.joinToString("\n").ifBlank { null },
                        retryable = true,
                        partiallyChangedFiles = changed,
                    )
                }
                null -> failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.UNEXPECTED,
                    message = "$displayName ended the turn with a stop reason this client does not know: " +
                        "${end.result.stopReason}.",
                    details = summary.joinToString("\n").ifBlank { null },
                    retryable = true,
                    partiallyChangedFiles = changed,
                )
            }
            TurnEnd.TimedOut -> failed(
                request = request,
                startedAt = startedAt,
                type = ExecutionError.Type.TIMEOUT,
                message = "$displayName did not finish the step within ${policy.promptTimeout}.",
                details = summary.joinToString("\n").ifBlank { null },
                retryable = true,
                partiallyChangedFiles = changed,
            )
            is TurnEnd.Failed -> when (val cause = end.cause) {
                is AcpRemoteException -> failed(
                    request = request,
                    startedAt = startedAt,
                    type = ExecutionError.Type.UNEXPECTED,
                    message = "$displayName answered the prompt with an error: " +
                        "${cause.error.code} ${cause.error.message}",
                    details = summary.joinToString("\n").ifBlank { null },
                    retryable = false,
                    partiallyChangedFiles = changed,
                )
                else -> {
                    // The stream is gone or the result unreadable: this process is finished
                    // with. The next call spawns a fresh one.
                    markDead(live)
                    failed(
                        request = request,
                        startedAt = startedAt,
                        type = ExecutionError.Type.TOOL_UNAVAILABLE,
                        message = "$displayName stopped mid-turn: ${cause.message}",
                        details = summary.joinToString("\n").ifBlank { null },
                        retryable = true,
                        partiallyChangedFiles = changed,
                    )
                }
            }
        }
    }

    private fun settleEndTurn(
        request: ExecutionRequest<*>,
        startedAt: Instant,
        fence: HarnessFence,
        turn: HarnessTurn,
        changed: List<String>,
        summary: List<String>,
        askedForChange: Boolean,
    ): ExecutionStatus {
        val offenders = changed.mapNotNull { path ->
            (fence.checkWrite(path) as? HarnessFence.Verdict.Refused)?.let { path to it.reason }
        }
        if (offenders.isNotEmpty()) {
            return failed(
                request = request,
                startedAt = startedAt,
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "$displayName changed files outside the file access scope: " +
                    offenders.joinToString { (path, _) -> "\"$path\"" } +
                    ". Nothing was reverted.",
                details = (offenders.map { (_, reason) -> reason } + summary).joinToString("\n"),
                retryable = false,
                partiallyChangedFiles = changed,
            )
        }
        if (changed.isEmpty() && askedForChange) {
            // AMPR-507: an agent that declines in prose ends the turn exactly as one that found
            // the work already done. The audit decides, not the agent's words: the step asked
            // for a change and none was made. The agent's account rides in `details`.
            return failed(
                request = request,
                startedAt = startedAt,
                type = ExecutionError.Type.UNEXPECTED,
                message = "$displayName ended the turn without changing any file.",
                details = summary.joinToString("\n").ifBlank { null },
                retryable = true,
                partiallyChangedFiles = emptyList(),
            )
        }
        val now = Clock.System.now()
        if (changed.isEmpty()) {
            return ExecutionStatus.Completed(
                executorId = id,
                timestamp = now,
                result = ExecutionOutcome.NoChanges.Success(
                    executorId = id,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = startedAt,
                    executionEndTimestamp = now,
                    message = summary.joinToString("\n").ifBlank { "$displayName reported no changes." },
                ),
            )
        }
        return ExecutionStatus.Completed(
            executorId = id,
            timestamp = now,
            result = ExecutionOutcome.CodeChanged.Success(
                executorId = id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startedAt,
                executionEndTimestamp = now,
                changedFiles = changed,
                validation = ExecutionResult(
                    codeChanges = ExecutionResultCodeChanges(
                        filesChanged = changed.map { path ->
                            FileChangeResult(
                                path = path,
                                operation = turn.operationFor(path) ?: EnvironmentFileOperation.MODIFY,
                                linesAdded = 0,
                                linesRemoved = 0,
                            )
                        },
                        commitMessage = "",
                        diffSummary = summary.joinToString("\n"),
                    ),
                    compilation = null,
                    linting = null,
                    tests = null,
                ),
            ),
        )
    }

    private fun failed(
        request: ExecutionRequest<*>,
        startedAt: Instant,
        type: ExecutionError.Type,
        message: String,
        retryable: Boolean,
        partiallyChangedFiles: List<String>?,
        details: String? = null,
    ): ExecutionStatus.Failed {
        val now = Clock.System.now()
        return ExecutionStatus.Failed(
            executorId = id,
            timestamp = now,
            result = ExecutionOutcome.CodeChanged.Failure(
                executorId = id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startedAt,
                executionEndTimestamp = now,
                error = ExecutionError(
                    type = type,
                    message = message,
                    details = details,
                    isRetryable = retryable,
                ),
                partiallyChangedFiles = partiallyChangedFiles,
            ),
        )
    }

    // ---- the process -----------------------------------------------------------------------

    /** The live, initialized connection, spawning and initializing one when there is none. */
    private suspend fun ensureLive(): Result<Live> = lifecycle.withLock {
        if (closed) return Result.failure(AcpException("$displayName has been closed"))
        live?.let { existing ->
            if (existing.connection.isOpen) return Result.success(existing)
            runCatching { existing.connection.close() }
            live = null
        }
        val connection = AcpConnection(transportFactory(), handler, scope, logger)
        connection.open().getOrElse { return Result.failure(it) }
        val initialized = try {
            withTimeoutOrNull(policy.startTimeout) {
                connection.initialize(
                    capabilities = ClientCapabilities(fs = FsCapabilities(readTextFile = true, writeTextFile = true)),
                    clientInfo = clientInfo,
                )
            }
        } catch (e: CancellationException) {
            connection.close()
            throw e
        } catch (e: Exception) {
            connection.close()
            return Result.failure(e)
        }
        if (initialized == null) {
            connection.close()
            return Result.failure(AcpException("the agent did not answer initialize within ${policy.startTimeout}"))
        }
        if (initialized.protocolVersion != ACP_PROTOCOL_VERSION) {
            connection.close()
            return Result.failure(
                AcpException(
                    "the agent speaks ACP version ${initialized.protocolVersion}; " +
                        "this client speaks $ACP_PROTOCOL_VERSION",
                ),
            )
        }
        logger.i { "$displayName is up: ${initialized.agentInfo?.name} ${initialized.agentInfo?.version ?: ""}" }
        Live(connection, initialized.agentInfo).also { live = it }.let { Result.success(it) }
    }

    /** The session for [workspace] under [runId], opened on first use. */
    private suspend fun sessionFor(
        live: Live,
        workspace: ExecutionWorkspace,
        runId: RunId?,
    ): Result<String> = lifecycle.withLock {
        val key = "${workspace.baseDirectory}\u0000${runId ?: ""}"
        live.sessions[key]?.let { return Result.success(it) }
        try {
            val sessionId = live.connection.newSession(cwd = workspace.baseDirectory).sessionId
            live.sessions[key] = sessionId
            Result.success(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun markDead(dead: Live) {
        lifecycle.withLock {
            if (live === dead) {
                runCatching { dead.connection.close() }
                live = null
            }
        }
    }

    // ---- the agent's requests --------------------------------------------------------------

    private val handler = object : AcpAgentRequestHandler {

        override suspend fun requestPermission(params: RequestPermissionParams): RequestPermissionResult {
            val active = activeTurn(params.sessionId)
            return when (val decision = active.fence.decide(params)) {
                is HarnessFence.Decision.Select -> {
                    decision.refusedBecause?.let { reason ->
                        logger.i { "Refusing '${params.toolCall.title ?: params.toolCall.toolCallId}': $reason" }
                        active.turn.recordRefusal(reason, active.fence.refusalKindOf(params.toolCall))
                    }
                    RequestPermissionResult(PermissionOutcome.selected(decision.optionId))
                }
                is HarnessFence.Decision.CancelTurn -> {
                    logger.w { "Cancelling the turn on ${params.sessionId}: ${decision.reason}" }
                    active.turn.recordRefusal(decision.reason, active.fence.refusalKindOf(params.toolCall))
                    runCatching { live?.connection?.cancel(params.sessionId) }
                    RequestPermissionResult(PermissionOutcome.cancelled())
                }
            }
        }

        override suspend fun readTextFile(params: ReadTextFileParams): ReadTextFileResult {
            val active = activeTurn(params.sessionId)
            active.fence.checkRead(params.path).refuse()
            val path = active.fence.absolutize(params.path).toPath()
            val text = try {
                fileSystem.read(path) { readUtf8() }
            } catch (e: IOException) {
                throw AcpRequestRefused(JsonRpcError.INVALID_PARAMS, "cannot read ${params.path}: ${e.message}")
            }
            return ReadTextFileResult(sliceLines(text, params.line, params.limit))
        }

        override suspend fun writeTextFile(params: WriteTextFileParams) {
            val active = activeTurn(params.sessionId)
            active.fence.checkWrite(params.path).refuse()
            val path = active.fence.absolutize(params.path).toPath()
            val existed = fileSystem.exists(path)
            try {
                path.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(path) { writeUtf8(params.content) }
            } catch (e: IOException) {
                throw AcpRequestRefused(JsonRpcError.INVALID_PARAMS, "cannot write ${params.path}: ${e.message}")
            }
            val relative = active.fence.relativize(params.path) ?: params.path
            active.turn.recordServedWrite(
                relativePath = relative,
                operation = if (existed) EnvironmentFileOperation.MODIFY else EnvironmentFileOperation.CREATE,
            )
        }

        private suspend fun activeTurn(sessionId: String): ActiveTurn =
            turnsLock.withLock { turns[sessionId] }
                ?: throw AcpRequestRefused(JsonRpcError.INVALID_REQUEST, "no turn is open on session $sessionId")

        /**
         * The kind of refusal [call] met: what the fence refuses an `execute` call for is the
         * shell policy, and any other call a path. A call the fence would allow was refused
         * for the options it came with.
         */
        private fun HarnessFence.refusalKindOf(call: PermissionToolCall): HarnessTurn.RefusalKind = when {
            refusalFor(call) == null -> HarnessTurn.RefusalKind.PROTOCOL
            call.kind == ToolKind.EXECUTE -> HarnessTurn.RefusalKind.SHELL
            else -> HarnessTurn.RefusalKind.PATH
        }

        private fun HarnessFence.Verdict.refuse() {
            if (this is HarnessFence.Verdict.Refused) throw AcpRequestRefused(JsonRpcError.FENCE_REFUSED, reason)
        }
    }

    private fun sliceLines(text: String, line: Int?, limit: Int?): String {
        if (line == null && limit == null) return text
        val lines = text.lines()
        val from = ((line ?: 1) - 1).coerceIn(0, lines.size)
        val to = limit?.let { (from + it).coerceAtMost(lines.size) } ?: lines.size
        return lines.subList(from, to).joinToString("\n")
    }

    companion object
}
