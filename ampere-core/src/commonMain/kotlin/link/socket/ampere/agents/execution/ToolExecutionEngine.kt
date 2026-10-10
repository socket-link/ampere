package link.socket.ampere.agents.execution

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PermissionDeniedEvent
import link.socket.ampere.agents.domain.event.PermissionDeniedReason
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.GateResult
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.PlugPermissionGate
import link.socket.ampere.plug.permission.PlugToolCall
import link.socket.ampere.plug.permission.UserGrants

/**
 * Engine for executing tools with LLM-generated parameters.
 *
 * This component bridges the gap between high-level intent and concrete tool
 * execution. It uses the LLM to generate the specific parameters each tool needs,
 * then executes the tool through the executor framework.
 *
 * The execution flow:
 * 1. Extract intent from the execution request
 * 2. Use a ParameterStrategy to build tool-specific prompts
 * 3. Call LLM to generate parameters
 * 4. Enrich the execution request with generated parameters
 * 5. Execute via the executor
 * 6. Record the outcome in episodic memory, when a store is wired
 * 7. Return the execution outcome
 *
 * Usage:
 * ```kotlin
 * val engine = ToolExecutionEngine(llmService, executor, executorId)
 *
 * // Register parameter strategies for different tools
 * engine.registerStrategy("create_issues", ProjectParams.IssueCreation(...))
 * engine.registerStrategy("ask_human", ProjectParams.HumanEscalation(...))
 *
 * // Execute a tool
 * val outcome = engine.execute(tool, request)
 * ```
 *
 * @property llmService The LLM service for generating parameters
 * @property executor The executor for running tools
 * @property executorId ID of the executor/agent running tools
 * @property runId Ambient Arc-run identity (AMPR-351), stamped onto every request this
 *   engine dispatches so run-attributable tools — today
 *   [ToolAskHuman][link.socket.ampere.agents.execution.tools.ToolAskHuman] — can carry it
 *   into the output they produce. Null leaves dispatched requests unattributed, which is
 *   correct for tool calls made outside a run.
 * @property outcomeRepository Episodic memory for the outcomes this engine produces (AMPR-406,
 *   F20). This is the *persisting path*: when a store is wired, every [ExecutionOutcome]
 *   returned by [execute] is recorded against the request's ticket and run, which is what
 *   makes `OutcomeService` and the CLI `outcomes` command read a store something writes.
 *   Null records nothing — correct for tests and for engines built without a database.
 */
class ToolExecutionEngine(
    private val llmService: AgentLLMService,
    private val executor: Executor,
    private val executorId: ExecutorId,
    private val eventApi: AgentEventApi? = null,
    private val userGrantProvider: suspend (PlugManifest) -> UserGrants = { UserGrants() },
    private val runId: RunId? = null,
    private val outcomeRepository: OutcomeMemoryRepository? = null,
) {

    private val strategies = mutableMapOf<String, ParameterStrategy>()

    /**
     * Registers a parameter generation strategy for a tool.
     *
     * @param toolId The tool ID this strategy handles
     * @param strategy The strategy for generating parameters
     */
    fun registerStrategy(toolId: String, strategy: ParameterStrategy) {
        strategies[toolId] = strategy
    }

    /**
     * Executes a tool with LLM-generated parameters, and records what came of it.
     *
     * The one public entry point, so it is also the one place every outcome this engine
     * produces passes through — a refusal before dispatch (no intent, permission denied, an
     * unsupported tool) just as much as a tool's own result. [recordOutcome] is called from
     * here rather than from [executeViaExecutor] for that reason: a dispatch that never
     * happened is still an attempt, and a failure is the more valuable half of the learning
     * signal.
     *
     * That is deliberately a *wider* net than the `ToolExecutionStarted` /
     * `ToolExecutionCompleted` pair [publishToolStarted] writes one layer down (AMPR-389),
     * and the two are not meant to agree: a pair in the trace means a tool actually ran,
     * while an outcome row means an attempt was made. A permission-denied call has a row and
     * no pair, and that asymmetry is the point of each.
     *
     * @param tool The tool to execute
     * @param request The execution request containing context and intent
     * @return ExecutionOutcome indicating success or failure
     */
    suspend fun execute(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        val outcome = produceOutcome(tool, request)
        recordOutcome(tool, request, outcome)
        return outcome
    }

    /**
     * Everything [execute] does bar the recording: strategy selection, the permission gate,
     * parameter generation, and the executor call. Named apart from [dispatch], which is the
     * executor call alone and the span the tool-event pair brackets.
     */
    private suspend fun produceOutcome(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        val startTime = Clock.System.now()

        // Extract intent from request
        val intent = request.context.instructions
        if (intent.isBlank()) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "Cannot execute tool: no intent found in request",
            )
        }

        val permissionFailure = checkPlugPermissions(tool, request, startTime)
        if (permissionFailure != null) {
            return permissionFailure
        }

        // Check for MCP tools (not yet supported)
        if (tool is McpTool) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "MCP tool execution not yet supported",
            )
        }

        // Get strategy for this tool — prefer the tool's own strategy, fall back to
        // the externally-registered map for legacy registrations.
        val strategy = tool.parameterStrategy ?: strategies[tool.id]
        if (strategy == null) {
            // No strategy registered - try generic execution
            return executeGenericTool(tool, request, startTime)
        }

        // Generate parameters using strategy. A strategy may refuse outright here — e.g. a
        // code tool whose request carries no pinned workspace (AMPR-300) — and that refusal
        // has to surface as a typed failure before any LLM call is spent on it.
        val prompt = try {
            strategy.buildPrompt(tool, request, intent)
        } catch (e: Exception) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "Cannot execute tool '${tool.id}': ${e.message}",
            )
        }
        val enrichedRequest = try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = strategy.systemMessage,
                maxTokens = strategy.maxTokens,
                // AMPR-389: parameter generation is a model call Execute makes, so it is
                // routed and filed as one. Untagged it had no phase and no run, and
                // `ArcTraceProjection` bucketed it under UNKNOWN.
                routingContext = RoutingContext(
                    phase = CognitivePhase.EXECUTE,
                    agentId = executorId,
                    workflowId = request.effectiveRunId(),
                ),
            )
            strategy.parseAndEnrichRequest(jsonResponse.rawJson, request)
        } catch (e: Exception) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "Failed to generate parameters: ${e.message}",
            )
        }

        // Execute via executor
        return executeViaExecutor(tool, enrichedRequest, startTime, request)
    }

    /**
     * Executes a tool without a registered strategy (generic execution).
     */
    private suspend fun executeGenericTool(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        startTime: Instant,
    ): ExecutionOutcome {
        // For tools without strategies, try direct execution
        return try {
            executeViaExecutor(tool, request, startTime, request)
        } catch (e: Exception) {
            createFailure(
                request = request,
                startTime = startTime,
                message = "Generic tool execution failed: ${e.message}",
            )
        }
    }

    /**
     * Executes the tool through the executor framework.
     *
     * The single funnel every dispatch path reaches, and so where the run id is stamped
     * (AMPR-351): a [ParameterStrategy] builds a fresh [ExecutionRequest] to carry its
     * generated parameters, dropping whatever [originalRequest] stated, so re-stamping
     * here — rather than before enrichment — is what actually reaches the tool.
     */
    private suspend fun executeViaExecutor(
        tool: Tool<*>,
        enrichedRequest: ExecutionRequest<*>,
        startTime: Instant,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionOutcome {
        // The caller's own run wins over this engine's: a request that already names a run
        // was dispatched by something closer to it than the reasoning unit that built us.
        val runScopedRequest = enrichedRequest.withRunId(originalRequest.runId ?: runId)
        val dispatchRunId = runScopedRequest.runId
        val invocationId = generateUUID("tool-invocation", tool.id, executorId)
        val dispatchedAt = Clock.System.now()
        publishToolStarted(tool, invocationId, dispatchedAt, dispatchRunId)
        val outcome = dispatch(tool, runScopedRequest, startTime, originalRequest)
        publishToolCompleted(tool, invocationId, dispatchedAt, outcome, dispatchRunId)
        return outcome
    }

    /**
     * The executor call itself, with every failure mode turned into an [ExecutionOutcome]
     * so [executeViaExecutor] always has an outcome to complete the pair with.
     */
    private suspend fun dispatch(
        tool: Tool<*>,
        runScopedRequest: ExecutionRequest<*>,
        startTime: Instant,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionOutcome {
        return try {
            when (tool) {
                is FunctionTool<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val typedTool = tool as Tool<ExecutionContext>

                    @Suppress("UNCHECKED_CAST")
                    val typedRequest = runScopedRequest as ExecutionRequest<ExecutionContext>

                    val statusFlow = executor.execute(typedRequest, typedTool)
                    val finalStatus = statusFlow.last()

                    when (finalStatus) {
                        is ExecutionStatus.Completed -> finalStatus.result
                        is ExecutionStatus.Failed -> finalStatus.result
                        else -> createFailure(
                            request = originalRequest,
                            startTime = startTime,
                            message = "Unexpected execution status: ${finalStatus::class.simpleName}",
                        )
                    }
                }
                is McpTool -> {
                    createFailure(
                        request = originalRequest,
                        startTime = startTime,
                        message = "MCP tool execution not yet supported",
                    )
                }
            }
        } catch (e: Exception) {
            createFailure(
                request = originalRequest,
                startTime = startTime,
                message = "Tool execution failed: ${e.message}",
            )
        }
    }

    /**
     * The tool is about to be dispatched (AMPR-389, F20's lift).
     *
     * This is the live path's producer of the `ToolExecutionStarted` / `ToolExecutionCompleted`
     * pair that `ArcTraceProjection` joins into `ArcRunTrace.toolCalls`. It wraps the executor
     * call and nothing else: a dispatch refused by [PlugPermissionGate] never reaches here and
     * is reported by [PermissionDeniedEvent] instead, so a pair in the trace means a tool
     * actually ran.
     */
    private suspend fun publishToolStarted(
        tool: Tool<*>,
        invocationId: String,
        dispatchedAt: Instant,
        dispatchRunId: RunId?,
    ) {
        val door = eventApi ?: return
        withContext(NonCancellable) {
            door.publish(
                ToolEvent.ToolExecutionStarted(
                    eventId = generateUUID("tool-execution-started", invocationId),
                    timestamp = dispatchedAt,
                    eventSource = EventSource.Agent(door.agentId),
                    urgency = Urgency.LOW,
                    invocationId = invocationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    runId = dispatchRunId,
                ),
                runId = dispatchRunId,
            )
        }
    }

    /**
     * The pair to [publishToolStarted], carrying the outcome's verdict and the time the
     * dispatch took. Under [NonCancellable]: the tool has already run its side effects by
     * the time this is written, and the projection reads a start with no completion as a
     * call still in flight — which would be a lie about a call that finished.
     */
    private suspend fun publishToolCompleted(
        tool: Tool<*>,
        invocationId: String,
        dispatchedAt: Instant,
        outcome: ExecutionOutcome,
        dispatchRunId: RunId?,
    ) {
        val door = eventApi ?: return
        val completedAt = Clock.System.now()
        val success = outcome is ExecutionOutcome.Success
        withContext(NonCancellable) {
            door.publish(
                ToolEvent.ToolExecutionCompleted(
                    eventId = generateUUID("tool-execution-completed", invocationId),
                    timestamp = completedAt,
                    eventSource = EventSource.Agent(door.agentId),
                    urgency = if (success) Urgency.LOW else Urgency.MEDIUM,
                    invocationId = invocationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    success = success,
                    durationMs = (completedAt - dispatchedAt).inWholeMilliseconds,
                    errorMessage = outcome.failureMessageOrNull(),
                    runId = dispatchRunId,
                ),
                runId = dispatchRunId,
            )
        }
    }

    /**
     * Writes [outcome] into episodic memory (AMPR-406), under the run the call belongs to.
     *
     * Under [NonCancellable]: by the time this runs the tool has already acted, so a
     * cancellation landing here would lose the record of something that happened — the same
     * reason the provider-call telemetry and the Arc's completion manifest are written this
     * way.
     *
     * Best-effort by contract. [OutcomeMemoryRepository.recordOutcome] already returns its
     * failure as a [Result] rather than throwing, and a store that throws anyway is swallowed:
     * a memory write must not change what the tool call reports, and the caller has the
     * outcome in hand either way.
     */
    private suspend fun recordOutcome(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        outcome: ExecutionOutcome,
    ) {
        val repository = outcomeRepository ?: return
        withContext(NonCancellable) {
            try {
                repository.recordOutcome(
                    ticketId = request.context.ticket.id,
                    executorId = executorId,
                    approach = approachOf(tool, request),
                    outcome = outcome,
                    // The outcome's own end time, not "now": the repository derives the
                    // recorded duration from it, and that duration is the tool's, not the
                    // tool's plus however long the write queued behind the IO dispatcher.
                    timestamp = outcome.executionEndTimestamp,
                    // The same run the dispatch was stamped with — the caller's wins over
                    // this engine's. Stripping it here would hide the row from
                    // `ArcTraceProjection`.
                    runId = request.effectiveRunId(),
                )
            } catch (_: Throwable) {
                // Deliberate; see the KDoc above.
            }
        }
    }

    /**
     * The run this dispatch belongs to. Same precedence as [executeViaExecutor]: a request
     * that already names a run was dispatched by something closer to it than the reasoning
     * unit that built this engine.
     */
    private fun ExecutionRequest<*>.effectiveRunId(): RunId? =
        this.runId ?: this@ToolExecutionEngine.runId

    /**
     * The searchable description of what was tried — the field `findSimilarOutcomes` matches on.
     *
     * The tool id leads so a search can find every attempt made with one tool, and the intent
     * follows. A request with no instructions of its own falls back to the ticket's
     * description, which is what the intent would have been derived from anyway.
     */
    private fun approachOf(tool: Tool<*>, request: ExecutionRequest<*>): String {
        val intent = request.context.instructions.ifBlank { request.context.ticket.description }
        return "${tool.id}: $intent"
    }

    /**
     * Creates a failure outcome.
     */
    private fun createFailure(
        request: ExecutionRequest<*>,
        startTime: Instant,
        message: String,
    ): ExecutionOutcome {
        return ExecutionOutcome.NoChanges.Failure(
            executorId = executorId,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            message = message,
        )
    }

    private suspend fun checkPlugPermissions(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        startTime: Instant,
    ): ExecutionOutcome? {
        val manifest = tool.plugManifest ?: return null
        val userGrants = userGrantProvider(manifest)
        val gateResult = PlugPermissionGate.check(
            toolCall = PlugToolCall(
                plugId = manifest.id,
                toolId = tool.id,
            ),
            manifest = manifest,
            userGrants = userGrants,
        )

        return when (gateResult) {
            GateResult.Allow -> null
            is GateResult.DenyMissing -> createPermissionDeniedFailure(
                request = request,
                startTime = startTime,
                tool = tool,
                manifest = manifest,
                permission = gateResult.permission,
                reason = PermissionDeniedReason.MISSING_GRANT,
            )
            is GateResult.DenyRevoked -> createPermissionDeniedFailure(
                request = request,
                startTime = startTime,
                tool = tool,
                manifest = manifest,
                permission = gateResult.permission,
                reason = PermissionDeniedReason.REVOKED_GRANT,
            )
        }
    }

    private suspend fun createPermissionDeniedFailure(
        request: ExecutionRequest<*>,
        startTime: Instant,
        tool: Tool<*>,
        manifest: PlugManifest,
        permission: PlugPermission,
        reason: PermissionDeniedReason,
    ): ExecutionOutcome {
        eventApi?.publish(
            PermissionDeniedEvent(
                eventId = generateUUID("permission-denied", manifest.id.value, tool.id, executorId),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(eventApi.agentId),
                urgency = Urgency.HIGH,
                plugId = manifest.id.value,
                toolId = tool.id,
                toolName = tool.name,
                permission = permission,
                reason = reason,
            ),
        )

        return createFailure(
            request = request,
            startTime = startTime,
            message = "Permission denied for plug '${manifest.id.value}' tool '${tool.id}': " +
                "$reason for $permission",
        )
    }
}

/**
 * The message a failed [ExecutionOutcome] carries, for the `errorMessage` on the tool-event
 * pair. `else` covers the success and blank variants, and any failure variant added later —
 * a missing message is better than a pair that stops being published.
 */
private fun ExecutionOutcome.failureMessageOrNull(): String? = when (this) {
    is ExecutionOutcome.NoChanges.Failure -> message
    is ExecutionOutcome.CodeReading.Failure -> error.message
    is ExecutionOutcome.CodeChanged.Failure -> error.message
    is ExecutionOutcome.IssueManagement.Failure -> error.message
    is ExecutionOutcome.GitOperation.Failure -> error.message
    is ExecutionOutcome.Planning.Failure -> error.message
    else -> null
}

/**
 * Strategy for generating tool-specific parameters.
 *
 * Each tool type can have its own strategy that knows how to:
 * - Build the appropriate LLM prompt
 * - Parse the LLM response
 * - Create the enriched ExecutionRequest with generated parameters
 *
 * Implementations should be tool-specific, e.g.:
 * - ProjectParams.IssueCreation for ToolCreateIssues
 * - CodeParams.CodeWriting for ToolWriteCodeFile
 * - ProjectParams.HumanEscalation for ToolAskHuman
 */
interface ParameterStrategy {

    /** System message for the LLM call */
    val systemMessage: String
        get() = "You are a parameter generation system. Generate parameters as valid JSON."

    /** Maximum tokens for the LLM response */
    val maxTokens: Int
        get() = 2000

    /**
     * Builds the LLM prompt for generating parameters.
     *
     * @param tool The tool being executed
     * @param request The original execution request
     * @param intent The high-level intent to accomplish
     * @return The prompt string for the LLM
     */
    fun buildPrompt(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
    ): String

    /**
     * Parses the LLM response and creates an enriched execution request.
     *
     * @param jsonResponse The raw JSON response from the LLM
     * @param originalRequest The original execution request
     * @return An enriched execution request with generated parameters
     */
    fun parseAndEnrichRequest(
        jsonResponse: String,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionRequest<*>
}
