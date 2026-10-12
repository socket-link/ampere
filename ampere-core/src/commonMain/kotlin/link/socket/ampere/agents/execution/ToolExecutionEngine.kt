package link.socket.ampere.agents.execution

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.definition.AgentId
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
import link.socket.ampere.agents.execution.executor.McpExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.request.withExecutorId
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.tools.mcp.McpCallArguments
import link.socket.ampere.agents.tools.mcp.McpToolExecutor
import link.socket.ampere.agents.tools.mcp.ServerManager
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
 * 5. Execute via the executor — once a [PreDispatchCheck] in the caller's coroutine
 *    context, if there is one, has let the final parameters through (AMPR-454)
 * 6. Record the outcome in episodic memory, when a store is wired and the tool had
 *    something to report
 * 7. Return the execution outcome
 *
 * Steps 2–4 are skipped when the call's parameters are already settled — see
 * [settledArguments] — so "with LLM-generated parameters" describes the fallback, not every
 * dispatch.
 *
 * Where a tool's parameters come from, in order (AMPR-411):
 * 1. its own [Tool.parameterStrategy], then a strategy registered here for its id. Needed by
 *    any tool whose parameters are a typed domain object, because only a strategy can promote
 *    the request into the [ExecutionContext] subtype carrying it.
 * 2. the arguments the request already carries, when they pass the tool's
 *    [FunctionTool.argumentSchema] — no model call.
 * 3. a [SchemaParameterStrategy] over that schema — one model call, answer validated.
 * 4. nothing: the tool is dispatched with the request as it came.
 *
 * An [McpTool] always lands on 4 unless a strategy was registered for its id, because 2 and 3
 * are keyed on [FunctionTool.argumentSchema] and an MCP tool declares `inputSchema` instead.
 * Its `tools/call` arguments are the envelope `McpCallArguments` derives from the request's
 * context (AMPR-341, AMPR-401), not [ExecutionRequest.arguments].
 *
 * Usage:
 * ```kotlin
 * val engine = ToolExecutionEngine(llmService, executor, actorId)
 *
 * // Register parameter strategies for tools whose parameters are a typed domain object
 * engine.registerStrategy("create_issues", ProjectParams.IssueCreation(...))
 * engine.registerStrategy("ask_human", ProjectParams.HumanEscalation(...))
 *
 * // A tool that declares an argumentSchema needs no registration
 *
 * // Execute a tool
 * val outcome = engine.execute(tool, request)
 * ```
 *
 * Every call names two parties, and never with one string (AMPR-443, F17): [actorId] is the
 * agent that decided the call, and the performer is the `Executor.id` that runs it — [executor]'s
 * for a [FunctionTool], [MCP_EXECUTOR_ID] for an [McpTool] (see [performerOf]). The engine
 * stamps the performer onto the dispatched context, the tool-event pair and the outcome row,
 * and reads [actorId] wherever "who" means the agent: the parameter call's routing, the
 * permission-denied event, the outcome row's actor.
 *
 * @property llmService The LLM service for generating parameters
 * @property executor The executor for running tools
 * @property actorId The agent this engine dispatches for — the decider, never the performer
 * @property runId Ambient Arc-run identity (AMPR-351), stamped onto every request this
 *   engine dispatches so run-attributable tools — today
 *   [ToolAskHuman][link.socket.ampere.agents.execution.tools.ToolAskHuman] — can carry it
 *   into the output they produce. Null leaves dispatched requests unattributed, which is
 *   correct for tool calls made outside a run.
 * @property outcomeRepository Episodic memory for the outcomes this engine produces (AMPR-406,
 *   F20). This is the *persisting path*: when a store is wired, every [ExecutionOutcome]
 *   returned by [execute] — a blank one excepted, since a tool that had nothing to report
 *   left nothing to record (AMPR-420) — is recorded against the request's ticket and run,
 *   which is what makes `OutcomeService` and the CLI `outcomes` command read a store
 *   something writes. Null records nothing — correct for tests and for engines built
 *   without a database.
 * @property mcpServerManager Where an [McpTool] dispatched by this engine is sent (AMPR-401):
 *   either a [PlugContext][link.socket.ampere.plug.PlugContext]'s
 *   [mcpServerManager][link.socket.ampere.plug.PlugContext.mcpServerManager] for plug-sourced
 *   tools, or the discovery-side
 *   [McpServerManager][link.socket.ampere.agents.tools.mcp.McpServerManager]. Null means this
 *   engine cannot speak MCP and says so per call, which is correct for an engine wired with
 *   function tools only. Handing the route to the engine rather than stamping it onto the
 *   tools is what keeps the gate in front of it: the engine is the only holder of an MCP
 *   route for a [Tool], so an agent's MCP call cannot be made without passing
 *   [checkPlugPermissions] first.
 */
class ToolExecutionEngine(
    private val llmService: AgentLLMService,
    private val executor: Executor,
    private val actorId: AgentId,
    private val eventApi: AgentEventApi? = null,
    private val userGrantProvider: suspend (PlugManifest) -> UserGrants = { UserGrants() },
    private val runId: RunId? = null,
    private val outcomeRepository: OutcomeMemoryRepository? = null,
    private val mcpServerManager: ServerManager? = null,
) {

    private val strategies = mutableMapOf<String, ParameterStrategy>()

    /**
     * The MCP dispatch arm, built only when a [ServerManager] was supplied.
     *
     * Shared with the `McpTool.execute()` path rather than reimplemented, so there is one
     * request -> `tools/call` translation
     * ([McpCallArguments][link.socket.ampere.agents.tools.mcp.McpCallArguments]) and one
     * `ToolCallResult` -> [ExecutionOutcome] translation for every MCP call Ampere makes.
     */
    private val mcpToolExecutor: McpToolExecutor? =
        mcpServerManager?.let { McpToolExecutor(serverManager = it) }

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
     * produces passes through — a refusal before dispatch (no intent, permission denied, a
     * tool with nowhere to dispatch to) just as much as a tool's own result. [recordOutcome] is called from
     * here rather than from [executeViaExecutor] for that reason: a dispatch that never
     * happened is still an attempt, and a failure is the more valuable half of the learning
     * signal.
     *
     * That is deliberately a *wider* net than the `ToolExecutionStarted` /
     * `ToolExecutionCompleted` pair [publishToolStarted] writes one layer down (AMPR-389),
     * and the two are not meant to agree: a pair in the trace means a tool actually ran,
     * while an outcome row means an attempt was made. A permission-denied call has a row and
     * no pair, and that asymmetry is the point of each. A blank outcome is the mirror of it:
     * the tool ran, so it has a pair, and it left nothing to remember, so it has no row. A
     * held outcome is the same shape for a different reason: the call ran, and it has not
     * finished, so there is no succeeded-or-failed for the row to say (AMPR-425).
     *
     * @param tool The tool to execute
     * @param request The execution request containing context and intent
     * @return the tool's own outcome — a success or a failure — or [ExecutionOutcome.Blank]
     *   for a tool that ran and had nothing to report, or [ExecutionOutcome.Held] for one
     *   whose action is waiting on a person; the last two are neither
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
                tool = tool,
                request = request,
                startTime = startTime,
                message = "Cannot execute tool: no intent found in request",
            )
        }

        val permissionFailure = checkPlugPermissions(tool, request, startTime)
        if (permissionFailure != null) {
            return permissionFailure
        }

        // Get strategy for this tool — prefer the tool's own strategy, fall back to
        // the externally-registered map for legacy registrations.
        val strategy = tool.parameterStrategy ?: strategies[tool.id]
        if (strategy != null) {
            return executeWithStrategy(tool, request, intent, strategy, startTime)
        }

        // AMPR-411: a tool that declares its arguments needs no hand-written strategy. The
        // schema path is tried only after the two explicit ones, because a tool that has both
        // a schema and a strategy wants the strategy: only the strategy can promote the
        // request into the context that tool reads its parameters from.
        val schema = (tool as? FunctionTool<*>)?.argumentSchema
        if (schema == null) {
            // No strategy and no schema - try generic execution
            return executeGenericTool(tool, request, startTime)
        }

        val settled = settledArguments(schema, request)
        return if (settled != null) {
            executeViaExecutor(tool, request.withArguments(settled), startTime, request)
        } else {
            executeWithStrategy(tool, request, intent, SchemaParameterStrategy(schema), startTime)
        }
    }

    /**
     * The arguments this call can be dispatched with as it stands, or null when the model has
     * to be asked for them.
     *
     * Two cases settle a call without a parameter call, and both are the same check against
     * the same schema (AMPR-411):
     * - the plan step already stated its arguments (H11) and they pass [schema]. The planner
     *   has the schema in front of it when it emits a step, so for a tool it can fill in, the
     *   second model call was only ever asking a question already answered.
     * - [schema] declares no arguments. There is nothing to generate, and asking anyway could
     *   only produce fields the schema does not name — which are dropped.
     *
     * Inline arguments that *fail* the schema fall through to the parameter call rather than
     * failing the step: the planner's arguments are an optimisation, and the model call is
     * the path that was always there.
     */
    private fun settledArguments(
        schema: JsonObject,
        request: ExecutionRequest<*>,
    ): JsonObject? {
        val supplied = request.arguments
        val candidate = when {
            supplied != null -> supplied
            ToolArgumentSchema.declaresNoArguments(schema) -> JsonObject(emptyMap())
            else -> return null
        }
        return (ToolArgumentSchema.validate(schema, candidate) as? SchemaValidation.Valid)?.arguments
    }

    /**
     * Runs [strategy] to fill this call's parameters, then dispatches what it produced.
     *
     * The parameter call is a model call Execute makes, and the only one this engine makes.
     */
    private suspend fun executeWithStrategy(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
        strategy: ParameterStrategy,
        startTime: Instant,
    ): ExecutionOutcome {
        // Generate parameters using strategy. A strategy may refuse outright here — e.g. a
        // code tool whose request carries no pinned workspace (AMPR-300) — and that refusal
        // has to surface as a typed failure before any LLM call is spent on it.
        val prompt = try {
            strategy.buildPrompt(tool, request, intent)
        } catch (e: Exception) {
            return createFailure(
                tool = tool,
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
                    agentId = actorId,
                    workflowId = request.effectiveRunId(),
                ),
            )
            // AMPR-411: a SchemaParameterStrategy refuses here when the model's answer misses
            // a required argument or uses a value outside a declared enum, and the refusal
            // names the arguments at fault.
            strategy.parseAndEnrichRequest(jsonResponse.rawJson, request)
        } catch (e: Exception) {
            return createFailure(
                tool = tool,
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
                tool = tool,
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
     * (AMPR-351), the dispatching agent's file access scope re-applied (AMPR-414) and
     * the earlier steps' results restored (AMPR-408): a [ParameterStrategy] builds a
     * fresh [ExecutionRequest] to carry its generated parameters, dropping whatever
     * [originalRequest] stated, so re-stamping here — rather than before enrichment —
     * is what actually reaches the tool. The performer is stamped here for the same reason
     * (AMPR-443): only the engine knows which executor a call actually goes to, so the
     * context's [ExecutionContext.executorId] is overwritten with [performerOf] the tool, and a
     * tool that stamps its outcome from the context names the executor the pair names.
     *
     * [ExecutionRequest.arguments] is deliberately *not* re-stamped the same way. The run,
     * the scope and the prior results belong to the dispatcher, so the original's copy is
     * authoritative; the arguments are what enrichment produced, so [enrichedRequest]'s copy
     * is. Re-applying the original's would overwrite a [SchemaParameterStrategy]'s generated
     * arguments with the null the agent dispatched with (AMPR-411).
     *
     * Being the single funnel also makes this the one place a call's parameters are final
     * and the tool has not yet run, so it is where a [PreDispatchCheck] is consulted
     * (AMPR-454): after the re-stamping, before the tool-event pair opens. A refused call
     * therefore has no pair, like a permission-denied one.
     */
    private suspend fun executeViaExecutor(
        tool: Tool<*>,
        enrichedRequest: ExecutionRequest<*>,
        startTime: Instant,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionOutcome {
        // The caller's own run wins over this engine's: a request that already names a run
        // was dispatched by something closer to it than the reasoning unit that built us.
        val performer = performerOf(tool)
        val runScopedRequest = enrichedRequest
            .performedBy(performer)
            .withRunId(originalRequest.runId ?: runId)
            // AMPR-414: the scope the agent stamped is the only one in play; a
            // strategy has no business widening it and no way to state one of
            // its own.
            .withFileAccessScope(originalRequest.fileAccessScope)
            // AMPR-408: the strategy reads the earlier steps' results to build its
            // prompt and then drops them when it rebuilds the request, so a tool
            // that wants to see what came before it gets them back here.
            .withPriorResults(originalRequest.priorResults)
        preDispatchRefusal(tool, runScopedRequest, startTime, originalRequest)?.let { return it }
        val dispatchRunId = runScopedRequest.runId
        val invocationId = generateUUID("tool-invocation", tool.id, actorId)
        val dispatchedAt = Clock.System.now()
        publishToolStarted(tool, invocationId, dispatchedAt, dispatchRunId, performer)
        val outcome = dispatch(tool, runScopedRequest, startTime, originalRequest)
        publishToolCompleted(tool, invocationId, dispatchedAt, outcome, dispatchRunId, performer)
        return outcome
    }

    /**
     * The refusal of the [PreDispatchCheck] this call runs under, or null to dispatch it.
     *
     * Null with no check in the coroutine context, which is every caller except a hosted
     * run's step: nothing else installs one, so nothing else changes. A check that throws
     * refuses — a gate that could not answer has not approved anything. A check that had
     * to ask its gate again may have waited on a person for a long time, so the plug
     * permissions are read once more before the call goes: consent withdrawn while the
     * gate was deciding still refuses it.
     */
    private suspend fun preDispatchRefusal(
        tool: Tool<*>,
        runScopedRequest: ExecutionRequest<*>,
        startTime: Instant,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionOutcome? {
        val check = currentCoroutineContext()[PreDispatchCheck] ?: return null
        val verdict = try {
            check.review(tool, dispatchArgumentsOf(tool, runScopedRequest))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PreDispatchVerdict.Refuse(
                "the call could not be reviewed before dispatch: ${e::class.simpleName}: ${e.message}",
            )
        }
        return when (verdict) {
            is PreDispatchVerdict.Refuse -> createFailure(
                tool = tool,
                request = originalRequest,
                startTime = startTime,
                message = "Tool '${tool.id}' was not dispatched: ${verdict.reason}",
            )
            is PreDispatchVerdict.Proceed ->
                if (verdict.reviewed) checkPlugPermissions(tool, originalRequest, startTime) else null
        }
    }

    /**
     * What [tool] is about to receive, as a [PreDispatchCheck] is shown it.
     *
     * - An [McpTool] receives the `tools/call` envelope [McpCallArguments] derives from the
     *   request — not [ExecutionRequest.arguments], which the MCP path does not read — so
     *   that envelope is what it is shown.
     * - A [FunctionTool] with no hand-written strategy receives [ExecutionRequest.arguments]
     *   as the request now carries them: inline, settled against its schema, generated by a
     *   [SchemaParameterStrategy], or as dispatched.
     * - A [FunctionTool] with a hand-written [ParameterStrategy] may read its parameters off
     *   the [ExecutionContext] subtype the strategy promoted the request into, which no
     *   argument value describes. It is shown what [ExecutionRequest.arguments] holds and
     *   marked not [visible][DispatchArguments.visible], so a check cannot mistake it for an
     *   unchanged call.
     */
    private fun dispatchArgumentsOf(tool: Tool<*>, request: ExecutionRequest<*>): DispatchArguments =
        when (tool) {
            is McpTool -> DispatchArguments(arguments = McpCallArguments.forRequest(request), visible = true)
            is FunctionTool<*> -> {
                val strategy = tool.parameterStrategy ?: strategies[tool.id]
                DispatchArguments(
                    arguments = request.arguments ?: JsonNull,
                    visible = strategy == null || strategy is SchemaParameterStrategy,
                )
            }
        }

    /**
     * The executor call itself, with every failure mode turned into an [ExecutionOutcome]
     * so [executeViaExecutor] always has an outcome to complete the pair with.
     *
     * The two tool kinds leave by different doors on purpose. A [FunctionTool] runs through
     * the injected [Executor], which on the live path is a
     * [FunctionExecutor][link.socket.ampere.agents.execution.executor.FunctionExecutor] and
     * cannot speak MCP. An [McpTool] runs through [mcpToolExecutor], the same arm
     * `McpTool.execute()` uses, reached from here rather than by calling `tool.execute`:
     * a plug-sourced [McpTool] carries no executor of its own, and giving it one would be a
     * dispatch route that skips [checkPlugPermissions] (AMPR-401). The
     * [McpExecutor][link.socket.ampere.agents.execution.executor.McpExecutor] in the executor
     * framework is a third implementation of this dispatch that nothing constructs; it is not
     * used here.
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
                        // The tool ran and had nothing to report. Returned as itself rather
                        // than dressed as a success or a failure (AMPR-420).
                        is ExecutionStatus.Blank -> ExecutionOutcome.Blank
                        // Waiting on a person: the tool's own hold, unchanged (AMPR-425).
                        is ExecutionStatus.Held -> finalStatus.result
                        else -> createFailure(
                            tool = tool,
                            request = originalRequest,
                            startTime = startTime,
                            message = "Unexpected execution status: ${finalStatus::class.simpleName}",
                        )
                    }
                }
                is McpTool -> {
                    val mcpExecutor = mcpToolExecutor
                    if (mcpExecutor == null) {
                        createFailure(
                            tool = tool,
                            request = originalRequest,
                            startTime = startTime,
                            message = "Cannot execute MCP tool '${tool.id}': this engine was " +
                                "built without an MCP ServerManager",
                        )
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val typedRequest = runScopedRequest as ExecutionRequest<ExecutionContext>

                        when (val outcome = mcpExecutor.execute(tool, typedRequest)) {
                            is ExecutionOutcome -> outcome
                            else -> createFailure(
                                tool = tool,
                                request = originalRequest,
                                startTime = startTime,
                                message = "MCP tool '${tool.id}' returned an unexpected " +
                                    "outcome: ${outcome::class.simpleName}",
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            createFailure(
                tool = tool,
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
     *
     * The pair names both parties (AMPR-443): the agent as the event's source — the door's
     * identity — and [performer] as its `executorId`.
     */
    private suspend fun publishToolStarted(
        tool: Tool<*>,
        invocationId: String,
        dispatchedAt: Instant,
        dispatchRunId: RunId?,
        performer: ExecutorId,
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
                    executorId = performer,
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
        performer: ExecutorId,
    ) {
        val door = eventApi ?: return
        val completedAt = Clock.System.now()
        // A blank outcome is a tool that ran and had nothing to report. That is not a failure,
        // and a pair that read as one — unsuccessful, medium urgency, no message to say why —
        // would send a reader looking for a fault that is not there (AMPR-420).
        val success = outcome is ExecutionOutcome.Success || outcome is ExecutionOutcome.Blank
        // A held call is waiting on a person: not a success, and not a failure either, so it
        // carries no error message and no failure urgency — only `held` (AMPR-425).
        val held = outcome is ExecutionOutcome.Held
        withContext(NonCancellable) {
            door.publish(
                ToolEvent.ToolExecutionCompleted(
                    eventId = generateUUID("tool-execution-completed", invocationId),
                    timestamp = completedAt,
                    eventSource = EventSource.Agent(door.agentId),
                    urgency = if (success || held) Urgency.LOW else Urgency.MEDIUM,
                    invocationId = invocationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    success = success,
                    durationMs = (completedAt - dispatchedAt).inWholeMilliseconds,
                    errorMessage = outcome.failureMessageOrNull(),
                    runId = dispatchRunId,
                    held = held,
                    executorId = performer,
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
        // A blank outcome is no outcome: the tool ran and left nothing to remember, and the
        // value carries the placeholder timestamps of an initialisation value
        // (`Instant.DISTANT_PAST`), which the write below would use as the row's time and file
        // at the year -100001 (AMPR-420). The tool's pair is still published, so the call is
        // on the trace.
        if (outcome is ExecutionOutcome.Blank) return
        // A held call is neither: the store's `success` column can only say succeeded or
        // failed, and a call waiting on a person is not finished (AMPR-425). Its pair still
        // goes out, with `held = true`, so the call is on the trace.
        if (outcome is ExecutionOutcome.Held) return
        withContext(NonCancellable) {
            try {
                repository.recordOutcome(
                    ticketId = request.context.ticket.id,
                    // AMPR-443: the agent that decided the call, and the executor that ran it
                    // — two columns, where the agent's id used to be written as the executor.
                    actorId = actorId,
                    executorId = performerOf(tool),
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
     * The `Executor.id` that performs [tool] when this engine dispatches it (AMPR-443).
     *
     * A [FunctionTool] runs through the injected [executor]. An [McpTool] runs through the MCP
     * arm, which is not an [Executor] but does the same job, and is named by
     * [MCP_EXECUTOR_ID]. Never [actorId]: the performer and the decider are two parties.
     */
    private fun performerOf(tool: Tool<*>): ExecutorId = when (tool) {
        is FunctionTool<*> -> executor.id
        is McpTool -> MCP_EXECUTOR_ID
    }

    /**
     * This request with [performer] stamped as its context's executor. The cast is the
     * dispatch's own: [withExecutorId] returns the subtype it was given.
     */
    private fun ExecutionRequest<*>.performedBy(performer: ExecutorId): ExecutionRequest<*> {
        @Suppress("UNCHECKED_CAST")
        val typed = this as ExecutionRequest<ExecutionContext>
        return typed.copy(context = typed.context.withExecutorId(performer))
    }

    /**
     * Creates a failure outcome, attributed to the executor [tool] was bound for — a refusal
     * before dispatch included, since that executor is still who the call was headed to.
     */
    private fun createFailure(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        startTime: Instant,
        message: String,
    ): ExecutionOutcome {
        return ExecutionOutcome.NoChanges.Failure(
            executorId = performerOf(tool),
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            message = message,
        )
    }

    /**
     * The engine's own gate, for every path that reaches it directly. A plan step has
     * already been admitted by the [Breaker][link.socket.ampere.propel.Breaker] against the
     * same grants before it gets here (AMPR-440), so on that path this sees `Allow` unless
     * consent was withdrawn in between — and then it still refuses.
     */
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
        publishPermissionDenied(eventApi, actorId, manifest, tool, permission, reason)

        return createFailure(
            tool = tool,
            request = request,
            startTime = startTime,
            message = permissionDeniedMessage(manifest, tool, permission, reason),
        )
    }

    companion object {
        /**
         * The performer an [McpTool] dispatch is attributed to (AMPR-443): the MCP arm, under
         * the id [McpExecutor] would carry, since it does the same job and is not itself an
         * [Executor].
         */
        const val MCP_EXECUTOR_ID: ExecutorId = McpExecutor.DEFAULT_ID
    }
}

/**
 * A last look at a tool call before [ToolExecutionEngine] dispatches it (AMPR-454).
 *
 * Carried in the coroutine context rather than on the engine or the request, because the
 * caller that owns the decision — a hosted run, holding what its plan gate approved for one
 * step — is several layers above both: the engine is built once per agent, and the request
 * is built and rebuilt below the run. The engine reads it at its dispatch funnel, where the
 * parameters are final, and nowhere else.
 *
 * Absent on every path that does not install one, and nothing but a hosted run's step does,
 * so the engine dispatches exactly as before everywhere else. Internal: a check is a gate's
 * enforcement, and only the run that holds the gate's approval may install one.
 */
internal abstract class PreDispatchCheck : AbstractCoroutineContextElement(PreDispatchCheck) {

    /** Whether [tool] may be dispatched with [call]. Suspends for as long as deciding takes. */
    abstract suspend fun review(tool: Tool<*>, call: DispatchArguments): PreDispatchVerdict

    /** The context key a check is read back under. */
    companion object Key : CoroutineContext.Key<PreDispatchCheck>
}

/**
 * The arguments a tool is about to receive, as the engine can show them.
 *
 * @property arguments The value the tool will read, or [JsonNull] when it carries none.
 * @property visible False when the tool's parameters may live somewhere [arguments] does
 *   not show — a hand-written strategy's typed context. A check must treat such a call as
 *   changed: it cannot tell that it is not.
 */
internal data class DispatchArguments(
    val arguments: JsonElement,
    val visible: Boolean,
)

/** What a [PreDispatchCheck] decided about one call. */
internal sealed interface PreDispatchVerdict {

    /**
     * Dispatch it.
     *
     * @property reviewed Whether the check had to ask someone first. A reviewed call has its
     *   plug permissions read again, since the asking may have taken a long time.
     */
    data class Proceed(val reviewed: Boolean) : PreDispatchVerdict

    /** Do not dispatch it; the call fails with [reason] and the tool never runs. */
    data class Refuse(val reason: String) : PreDispatchVerdict
}

/**
 * Publishes the [PermissionDeniedEvent] for a plug tool call [PlugPermissionGate] refused.
 *
 * Shared by the two places a refusal is decided (AMPR-440): this engine's own gate, for
 * every path that reaches it directly, and the [Breaker][link.socket.ampere.propel.Breaker]
 * that admits each plan step before the step reaches the engine. One construction, so a
 * step the Breaker trips is on the record in exactly the shape the engine always put it
 * there. Null [eventApi] publishes nothing.
 *
 * [actorId] is the agent the refused call was made for (AMPR-443) — it keys the event id, as
 * the agent's id always did when it travelled under the name `executorId`.
 */
internal suspend fun publishPermissionDenied(
    eventApi: AgentEventApi?,
    actorId: AgentId,
    manifest: PlugManifest,
    tool: Tool<*>,
    permission: PlugPermission,
    reason: PermissionDeniedReason,
) {
    eventApi?.publish(
        PermissionDeniedEvent(
            eventId = generateUUID("permission-denied", manifest.id.value, tool.id, actorId),
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
}

/** The reason a refused plug tool call is reported with: the plug, the tool and the permission. */
internal fun permissionDeniedMessage(
    manifest: PlugManifest,
    tool: Tool<*>,
    permission: PlugPermission,
    reason: PermissionDeniedReason,
): String = "Permission denied for plug '${manifest.id.value}' tool '${tool.id}': $reason for $permission"

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
 *
 * With one exception: [SchemaParameterStrategy] is tool-*agnostic*, built from whatever
 * [FunctionTool.argumentSchema] a tool declares. Write an implementation by hand when a
 * tool's parameters are a typed domain object it reads off its own [ExecutionContext]
 * subtype; declare a schema instead when they are a flat set of named arguments (AMPR-411).
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
     * A strategy whose tool can consume what an earlier step produced renders
     * [ExecutionRequest.priorResults] into its prompt — through
     * [priorResultsSection], so every strategy renders them the same way and an
     * empty list adds nothing (AMPR-408). Without that, a step can only ever be
     * parameterised from its own description, and a plan cannot pass results
     * forward.
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
