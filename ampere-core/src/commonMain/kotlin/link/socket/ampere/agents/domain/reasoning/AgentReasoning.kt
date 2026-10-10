package link.socket.ampere.agents.domain.reasoning

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.tools.mcp.ServerManager
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.MissingUpstreamDecisionClientException
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.stateDigest
import link.socket.ampere.llm.decide.typeName
import link.socket.ampere.llm.decide.version
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.UserGrants

/**
 * Unified reasoning facade that composes all cognitive services.
 *
 * This is the primary interface agents use to access reasoning capabilities.
 * It combines all the individual services (LLM, perception, planning,
 * execution, evaluation, knowledge extraction) into a cohesive API.
 *
 * Per-phase context builders, planning prompt builders, and custom knowledge
 * extractors were deleted by AMPR-163 Task 11; spark-based agents now express
 * that guidance through their stacked `.spark.md` per-phase contributions and
 * fall through to the generic `KnowledgeExtractor.extractDefault` for
 * knowledge extraction. The Perceive phase's context builder came back in
 * AMPR-403 — a spark narrows the *system* prompt, so it has no way to put the
 * host's own observations of the world into the *user* prompt, which is what
 * the Perceive phase is asking about.
 *
 * Usage:
 * ```kotlin
 * val reasoning = AgentReasoning.create(config, executorId, eventApi, activePromptProvider) {
 *     agentRole = "Spark-Based Agent (ANALYTICAL)"
 *     availableTools = { effectiveTools }
 *
 *     execution {
 *         registerStrategy("create_issues", ProjectParams.IssueCreation(...))
 *     }
 * }
 *
 * val idea = reasoning.evaluatePerception(perception)
 * val plan = reasoning.generatePlan(task, ideas, relevantKnowledge)
 * val outcome = reasoning.executeTool(tool, request)
 * val knowledge = reasoning.extractKnowledge(outcome, task, plan)
 * ```
 *
 * @property config The agent configuration
 * @property settings The reasoning settings
 */
class AgentReasoning private constructor(
    private val config: AgentConfiguration?,
    private val settings: ReasoningSettings,
    private val eventApi: AgentEventApi? = null,
    private val mockResponses: MockReasoningResponses? = null,
    private val activePromptProvider: (() -> String?)? = null,
    private val runId: RunId? = null,
) {
    private val llmService: AgentLLMService? = config?.let {
        AgentLLMService(it, eventApi, activePromptProvider, it.upstreamLlmClient)
    }
    private val perceptionEvaluator: PerceptionEvaluator? = llmService?.let { PerceptionEvaluator(it) }
    private val planGenerator: PlanGenerator? = llmService?.let { PlanGenerator(it) }
    private val outcomeEvaluator: OutcomeEvaluator? = llmService?.let { OutcomeEvaluator(it) }

    // AMPR-389: Execute publishes its own steps. The door and the run are the two things
    // the executor needs to do that; without a door it stays silent, as before.
    private val planExecutor = PlanExecutor(
        executorId = settings.executorId,
        eventApi = eventApi,
        runId = runId,
    )

    private val toolExecutionEngine: ToolExecutionEngine? = if (llmService != null && settings.executor != null) {
        ToolExecutionEngine(
            llmService = llmService,
            executor = settings.executor,
            executorId = settings.executorId,
            eventApi = eventApi,
            userGrantProvider = settings.userGrantProvider,
            // AMPR-351: the run reaches tools on the request the engine dispatches, which
            // is how ToolAskHuman's Emissions get a non-null EmissionProvenance.runId.
            runId = runId,
            // AMPR-406: with a store wired, the engine is the persisting path for every
            // ExecutionOutcome it produces. Null leaves the engine recording nothing.
            outcomeRepository = settings.outcomeRepository,
            // AMPR-401: the engine is the one execute path, so an MCP-sourced tool is
            // dispatched by it too — but only if the host handed it a route. Null leaves
            // this unit unable to call MCP tools, which it already was.
            mcpServerManager = settings.mcpServerManager,
        ).also { engine ->
            settings.parameterStrategies.forEach { (toolId, strategy) ->
                engine.registerStrategy(toolId, strategy)
            }
        }
    } else {
        null
    }

    /**
     * Whether this unit can actually run a tool (AMPR-405).
     *
     * False means no [ToolExecutionEngine] was built — because no
     * [ReasoningSettings.executor] was supplied, or because there is no
     * configuration to build an LLM service from — and so every [executeTool] call
     * returns the "no executor configured" refusal below rather than dispatching
     * anything.
     *
     * Ask this *before* planning a cycle that is supposed to act. The alternative
     * is finding out one plan step at a time: a misconfigured agent plans normally,
     * nominates tools normally, and then refuses each of its own steps in turn,
     * which reads in a trace like several tool failures rather than one wiring
     * mistake. [link.socket.ampere.agents.definition.SparkBasedAgent.canExecuteTools]
     * surfaces this to the hosts that drive the loop.
     */
    val canExecuteTools: Boolean
        get() = toolExecutionEngine != null || mockResponses?.toolExecutor != null

    // ========================================================================
    // Perception
    // ========================================================================

    /**
     * Evaluates a perception and generates insights.
     *
     * The rendered prompt carries three things (AMPR-403): the state, through
     * [ReasoningSettings.perceptionContextBuilder] or
     * [defaultPerceptionContext] when the host supplied none; the
     * [Perception.ideas] handed in, verbatim; and the agent's tools. Before
     * AMPR-403 the state was rendered as `"State: $state"` and the ideas were
     * dropped, so every iteration asked the same question about nothing.
     */
    suspend fun <S : AgentState> evaluatePerception(perception: Perception<S>): Idea {
        // Use mock response if available
        mockResponses?.perceptionEvaluator?.let { evaluator ->
            @Suppress("UNCHECKED_CAST")
            return evaluator(perception as Perception<AgentState>)
        }

        val contextBuilder = settings.perceptionContextBuilder ?: ::defaultPerceptionContext
        return perceptionEvaluator?.evaluate(
            perception = perception,
            contextBuilder = { state -> contextBuilder(state) },
            agentRole = settings.agentRole,
            availableTools = settings.availableTools(),
            runId = runId,
        ) ?: throw IllegalStateException("No perception evaluator configured")
    }

    // ========================================================================
    // Planning
    // ========================================================================

    /**
     * Generates a plan for accomplishing a task.
     *
     * @param relevantKnowledge what Recall retrieved for [task]. It is rendered into the
     *   planning prompt, so omitting it is skipping Recall — the canonical failure mode
     *   `propel-loop.md` names, and the one AMPR-388 closed. The default
     *   exists for callers that genuinely have no memory service; pass the recalled list
     *   whenever there is one.
     */
    suspend fun generatePlan(
        task: Task,
        ideas: List<Idea>,
        relevantKnowledge: List<KnowledgeWithScore> = emptyList(),
    ): Plan {
        // Use mock response if available
        mockResponses?.planGenerator?.let { generator ->
            return generator(task, ideas)
        }

        return planGenerator?.generate(
            task = task,
            ideas = ideas,
            agentRole = settings.agentRole,
            availableTools = settings.availableTools(),
            relevantKnowledge = relevantKnowledge,
            taskFactory = settings.taskFactory,
            customPromptBuilder = null,
            runId = runId,
        ) ?: throw IllegalStateException("No plan generator configured")
    }

    // ========================================================================
    // Execution
    // ========================================================================

    /**
     * Executes a plan step by step.
     *
     * @param priorResults what the caller has already run, oldest first (AMPR-408). It
     *   seeds [StepContext.priorResults] so a step executor can pass earlier results
     *   into the request it dispatches; a caller at the start of a plan passes
     *   `emptyList()`.
     */
    suspend fun executePlan(
        plan: Plan,
        priorResults: List<StepOutcome>,
        stepExecutor: suspend (Task, StepContext) -> StepResult,
    ): PlanExecutionResult {
        return planExecutor.execute(plan, priorResults, stepExecutor)
    }

    /**
     * Executes a tool with LLM-generated parameters.
     */
    suspend fun executeTool(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        // Use mock response if available
        mockResponses?.toolExecutor?.let { executor ->
            return executor(tool, request)
        }

        return toolExecutionEngine?.execute(tool, request)
            ?: ExecutionOutcome.NoChanges.Failure(
                executorId = settings.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = Clock.System.now(),
                executionEndTimestamp = Clock.System.now(),
                message = NO_EXECUTOR_MESSAGE,
            )
    }

    // ========================================================================
    // Evaluation & Learning
    // ========================================================================

    /**
     * Evaluates outcomes and generates learnings.
     */
    suspend fun evaluateOutcomes(
        outcomes: List<Outcome>,
        memoryService: AgentMemoryService? = null,
        runId: RunId? = null,
    ): EvaluationResult {
        // Use mock response if available
        mockResponses?.outcomeEvaluator?.let { evaluator ->
            return evaluator(outcomes)
        }

        val effectiveRunId = runId ?: this.runId ?: outcomes.firstRunIdOrNull()
        val result = outcomeEvaluator?.evaluate(
            outcomes = outcomes,
            agentRole = settings.agentRole,
            contextBuilder = null,
            runId = effectiveRunId,
        ) ?: throw IllegalStateException("No outcome evaluator configured")

        // Store learnings in memory if available
        if (result.knowledge.isNotEmpty() && memoryService != null) {
            result.knowledge.forEach { knowledge ->
                memoryService.storeKnowledge(knowledge, runId = effectiveRunId)
            }
        }

        return result
    }

    /**
     * Extracts knowledge from a single outcome using the generic
     * `KnowledgeExtractor.extractDefault`. Per-agent custom extractors were
     * removed by AMPR-163 Task 11; agents now express role-specific learning
     * guidance through the `## When Learning` section of their `.spark.md`.
     */
    fun extractKnowledge(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome =
        KnowledgeExtractor.extractDefault(outcome, task, plan, settings.agentRole)

    // ========================================================================
    // Direct LLM Access
    // ========================================================================

    /**
     * Calls the LLM directly with a prompt.
     *
     * @param requirements Per-call capability requirement (AMPR-232). A caller that
     *   knows this particular call needs more than the agent's standing contract —
     *   a stricter [CapabilityRequirement.minRung], a longer context window — supplies
     *   it here and it lands on the [RoutingContext] the relay resolves against.
     *   Rung floors compose as the stricter of this and the agent's declared
     *   `AgentDefinition.minimumRung` (AMPR-229), so a per-call floor only ever
     *   *raises* the bar: agent `THREE` + call-site `FOUR` resolves against `FOUR`,
     *   and agent `FOUR` + call-site `THREE` still resolves against `FOUR`. Neither
     *   direction downgrades. Null keeps the agent's own floor.
     * @param phase Per-call cognitive phase (AMPR-273). Without it, the invocation's
     *   [RoutingContext.phase] is null and `ArcTraceProjection` files the call under
     *   `UNKNOWN`. Supplying it here tags the call the same way the phase-aware
     *   reasoning entry points do, without dropping to [AgentLLMService] directly.
     *   Null keeps today's behaviour.
     */
    suspend fun callLLM(
        prompt: String,
        systemMessage: String? = null,
        requirements: CapabilityRequirement? = null,
        phase: CognitivePhase? = null,
    ): String {
        // Use mock response if available
        mockResponses?.llmCall?.let { call ->
            return call(prompt)
        }

        return llmService?.call(
            prompt = prompt,
            systemMessage = systemMessage ?: "You are a ${settings.agentRole} agent.",
            routingContext = routingContext(requirements, phase),
        ) ?: throw IllegalStateException("No LLM service configured")
    }

    /**
     * Calls the LLM expecting a JSON response.
     *
     * @param requirements Per-call capability requirement (AMPR-232); see [callLLM].
     * @param phase Per-call cognitive phase (AMPR-273); see [callLLM].
     */
    suspend fun callLLMForJson(
        prompt: String,
        requirements: CapabilityRequirement? = null,
        phase: CognitivePhase? = null,
    ): LLMJsonResponse {
        return llmService?.callForJson(
            prompt = prompt,
            routingContext = routingContext(requirements, phase),
        )
            ?: throw IllegalStateException("No LLM service configured")
    }

    // ========================================================================
    // Decide (AMPR-384)
    // ========================================================================

    /**
     * Asks typed [questions] about [state] through the injected
     * [UpstreamDecisionClient][link.socket.ampere.llm.decide.UpstreamDecisionClient]
     * and records every judgment that comes back (AMPR-384).
     *
     * The second call kind beside [callLLM] and [callLLMForJson]. A decision call
     * generates no text: every answer is declared on its [Question] and the
     * response is one [Judgment][link.socket.ampere.llm.decide.Judgment] per
     * question id, with a measured confidence when the transport is a decision
     * model. Shadow only in W1 — nothing in the loop calls this yet.
     *
     * One [CognitiveEvent.JudgmentRecorded] per judgment is published through the
     * agent's event door, under the run id, carrying a digest of the state and
     * never the state. Without a door (no [eventApi]) nothing is recorded, as
     * with the `ProviderCall*` pair. A call that throws leaves no record.
     *
     * @param phase The cognitive phase asking, so the trace files the record under
     *   it (AMPR-273). Null files it under whatever phase is active.
     * @param causedBy The Action or write the judgment bears on, if any (F2). Goes
     *   on the record and on the stored row's `caused_by`.
     * @throws MissingUpstreamDecisionClientException when the configuration
     *   carries no decision transport. There is no fallback.
     */
    suspend fun decide(
        state: String,
        questions: Map<String, Question>,
        phase: CognitivePhase? = null,
        causedBy: EventId? = null,
    ): DecisionResponse {
        val configuration = config
            ?: throw IllegalStateException("No agent configuration; decide needs one")
        val client = configuration.upstreamDecisionClient
            ?: throw MissingUpstreamDecisionClientException(configuration.agentDefinition.name)

        val request = DecisionRequest(state = state, questions = questions)
        val startedAt = Clock.System.now()
        val response = client.decide(request, configuration.aiConfiguration)
        val latencyMs = (Clock.System.now() - startedAt).inWholeMilliseconds

        recordJudgments(request, response, latencyMs, phase, causedBy)
        return response
    }

    /**
     * Publishes one [CognitiveEvent.JudgmentRecorded] per judgment (J4). Under
     * [NonCancellable] for the same reason as the provider-call telemetry: the
     * judgment has been made and paid for by the time this runs, and a
     * cancellation landing here would lose a record of a call that happened.
     */
    private suspend fun recordJudgments(
        request: DecisionRequest,
        response: DecisionResponse,
        latencyMs: Long,
        phase: CognitivePhase?,
        causedBy: EventId?,
    ) {
        val door = eventApi ?: return
        val publishingAgentId = door.agentId
        val callId = generateUUID("decide", publishingAgentId)
        val digest = stateDigest(request.state)
        withContext(NonCancellable) {
            response.judgments.forEach { (questionId, judgment) ->
                val question = request.questions[questionId] ?: return@forEach
                door.publish(
                    CognitiveEvent.JudgmentRecorded(
                        eventId = generateUUID("judgment", publishingAgentId),
                        timestamp = Clock.System.now(),
                        eventSource = EventSource.Agent(publishingAgentId),
                        agentId = publishingAgentId,
                        callId = callId,
                        questionId = questionId,
                        questionVersion = question.version,
                        questionType = question.typeName,
                        stateDigest = digest,
                        answer = judgment.answer,
                        distribution = judgment.distribution,
                        confidence = judgment.confidence,
                        source = judgment.source,
                        band = null,
                        modelSnapshot = judgment.modelSnapshot,
                        locality = judgment.locality,
                        latencyMs = latencyMs,
                        usage = response.usage,
                        causedBy = causedBy,
                        cognitivePhase = phase,
                    ),
                    causedBy = causedBy,
                    runId = runId,
                )
            }
        }
    }

    private fun routingContext(
        requirements: CapabilityRequirement?,
        phase: CognitivePhase? = null,
    ): RoutingContext =
        RoutingContext(
            agentId = settings.executorId,
            agentRole = settings.agentRole,
            requirements = requirements,
            workflowId = runId,
            phase = phase,
        )

    companion object {
        /**
         * What [executeTool] reports when [canExecuteTools] is false (AMPR-405).
         *
         * Names the missing dependency and how to supply it, because the two ways to
         * reach this message are both wiring mistakes rather than runtime conditions,
         * and the message is the only evidence of either. Exposed so the hosts that
         * refuse a cycle up front can say the same thing the step-level refusal would
         * have said.
         */
        const val NO_EXECUTOR_MESSAGE: String =
            "No executor configured, so no tool can run: this agent's reasoning unit was " +
                "built without one (ReasoningSettings.executor). The agent factories supply " +
                "a FunctionExecutor by default; a hand-built agent must pass one."

        /**
         * Creates an AgentReasoning instance with the given configuration.
         */
        fun create(
            config: AgentConfiguration,
            executorId: ExecutorId,
            eventApi: AgentEventApi? = null,
            activePromptProvider: (() -> String?)? = null,
            runId: RunId? = null,
            configure: ReasoningSettingsBuilder.() -> Unit,
        ): AgentReasoning {
            val builder = ReasoningSettingsBuilder(executorId)
            builder.configure()
            return AgentReasoning(
                config = config,
                settings = builder.build(),
                eventApi = eventApi,
                activePromptProvider = activePromptProvider,
                runId = runId,
            )
        }

        /**
         * Creates an AgentReasoning instance for testing with mock responses.
         *
         * This factory allows tests to inject mock behaviors for all cognitive operations
         * without requiring a real LLM connection.
         *
         * Usage:
         * ```kotlin
         * val mockReasoning = AgentReasoning.createForTesting(executorId) {
         *     onPerception { perception -> Idea("Mock insight", "Analysis") }
         *     onPlanning { task, ideas -> Plan.ForTask(task, listOf(task), 1) }
         *     onToolExecution { tool, request -> ExecutionOutcome.Success(...) }
         *     onOutcomeEvaluation { outcomes -> EvaluationResult(...) }
         * }
         * ```
         */
        fun createForTesting(
            executorId: ExecutorId,
            configure: MockReasoningBuilder.() -> Unit,
        ): AgentReasoning {
            val builder = MockReasoningBuilder()
            builder.configure()
            val settings = ReasoningSettings(
                executorId = executorId,
                agentRole = "Test Agent",
                availableTools = { emptySet() },
                executor = null,
                taskFactory = DefaultTaskFactory,
                parameterStrategies = emptyMap(),
                // Deny-all is correct and permanent here, not an oversight (AMPR-348):
                // `config = null` below means `toolExecutionEngine` is never built
                // (it requires a non-null `llmService`), so this provider can never
                // actually be invoked. It exists only to satisfy `ReasoningSettings`'
                // non-nullable field, and mirrors the same safe fallback production
                // callers get when they don't wire a `UserGrantStore`.
                userGrantProvider = { UserGrants() },
                // Nothing to persist to, for the same reason: no engine is ever built here.
                outcomeRepository = null,
            )
            return AgentReasoning(
                config = null,
                settings = settings,
                eventApi = null,
                mockResponses = builder.build(),
            )
        }
    }
}

private fun List<Outcome>.firstRunIdOrNull(): RunId? =
    filterIsInstance<ExecutionOutcome>()
        .firstOrNull()
        ?.taskId
        ?.takeUnless { it.isBlank() }
        ?: firstOrNull()
            ?.id
            ?.takeUnless { it.isBlank() }

/**
 * Mock responses container for testing AgentReasoning.
 */
data class MockReasoningResponses(
    val perceptionEvaluator: ((Perception<AgentState>) -> Idea)? = null,
    val planGenerator: ((Task, List<Idea>) -> Plan)? = null,
    val toolExecutor: ((Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome)? = null,
    val outcomeEvaluator: ((List<Outcome>) -> EvaluationResult)? = null,
    val llmCall: ((String) -> String)? = null,
)

/**
 * Builder for configuring mock reasoning responses.
 */
class MockReasoningBuilder {
    private var perceptionEvaluator: ((Perception<AgentState>) -> Idea)? = null
    private var planGenerator: ((Task, List<Idea>) -> Plan)? = null
    private var toolExecutor: ((Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome)? = null
    private var outcomeEvaluator: ((List<Outcome>) -> EvaluationResult)? = null
    private var llmCall: ((String) -> String)? = null

    fun onPerception(handler: (Perception<AgentState>) -> Idea) {
        perceptionEvaluator = handler
    }

    fun onPlanning(handler: (Task, List<Idea>) -> Plan) {
        planGenerator = handler
    }

    fun onToolExecution(handler: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome) {
        toolExecutor = handler
    }

    fun onOutcomeEvaluation(handler: (List<Outcome>) -> EvaluationResult) {
        outcomeEvaluator = handler
    }

    fun onLLMCall(handler: (String) -> String) {
        llmCall = handler
    }

    fun build(): MockReasoningResponses = MockReasoningResponses(
        perceptionEvaluator = perceptionEvaluator,
        planGenerator = planGenerator,
        toolExecutor = toolExecutor,
        outcomeEvaluator = outcomeEvaluator,
        llmCall = llmCall,
    )
}

/**
 * Settings for configuring agent reasoning behaviour.
 *
 * AMPR-163 Task 11 removed the per-phase customisation fields
 * (perceptionContextBuilder, planningPromptBuilder, outcomeContextBuilder,
 * knowledgeExtractor): role-specific guidance now lives in stacked
 * `.spark.md` per-phase contributions instead of in agent-side Kotlin
 * builders.
 *
 * [perceptionContextBuilder] is the one of those four that came back
 * (AMPR-403). A spark narrows the *system* prompt, so it cannot carry the
 * host's observations of the world into the *user* prompt — which is the
 * whole of what the Perceive phase asks about.
 */
data class ReasoningSettings(
    val executorId: ExecutorId,
    val agentRole: String,
    /**
     * The tools to offer the planner and the perception evaluator, evaluated at
     * every call rather than once at construction.
     *
     * It is a provider because the set is not static: a spark-based agent
     * narrows its tools through its live spark stack
     * ([AutonomousAgent.effectiveTools][link.socket.ampere.agents.definition.AutonomousAgent.effectiveTools]),
     * and a set captured when the reasoning unit was built would keep offering
     * tools a later spark has since withdrawn (AMPR-400).
     */
    val availableTools: () -> Set<Tool<*>>,
    val executor: Executor?,
    val taskFactory: TaskFactory,
    val parameterStrategies: Map<String, ParameterStrategy>,
    val userGrantProvider: suspend (PlugManifest) -> UserGrants,
    /**
     * How the Perceive phase renders the state into the prompt (AMPR-403).
     *
     * Null falls through to [defaultPerceptionContext], which renders the
     * current task rather than `toString()` of the state. A host that knows
     * more about its world than the memory cells do — open tickets, inbound
     * messages, whatever it is perceiving on the agent's behalf — supplies its
     * own here.
     *
     * Typed over [AgentState] rather than the agent's own state type because
     * [ReasoningSettings] is not generic; a builder that needs the narrower
     * type casts inside its own lambda.
     */
    val perceptionContextBuilder: ((AgentState) -> String)? = null,
    /**
     * Episodic memory for the outcomes this unit's tool calls produce (AMPR-406). Handed
     * straight to [ToolExecutionEngine]; null leaves tool outcomes unrecorded.
     */
    val outcomeRepository: OutcomeMemoryRepository? = null,
    /**
     * Where this unit's MCP tool calls are sent (AMPR-401) — a
     * [PlugContext.mcpServerManager][link.socket.ampere.plug.PlugContext.mcpServerManager]
     * for a plug's servers, or
     * [McpServerManager][link.socket.ampere.agents.tools.mcp.McpServerManager] for
     * discovered ones. Handed straight to [ToolExecutionEngine], which gates the call
     * before it uses the route. Null leaves MCP tools undispatchable, with that as the
     * failure's stated reason.
     */
    val mcpServerManager: ServerManager? = null,
)

/**
 * Builder for [ReasoningSettings].
 *
 * The DSL is intentionally narrow after AMPR-163 Task 11. [execution]
 * registers parameter strategies and the user-grant provider, and
 * [perceptionContextBuilder] is the one per-phase context hook that came back
 * (AMPR-403). The rest of the per-phase prompt customisation stays in the
 * `.spark.md` artifacts the agent stacks at construction time.
 */
class ReasoningSettingsBuilder(private val executorId: ExecutorId) {
    var agentRole: String = "Agent"

    /**
     * Provider for the tools offered to the planner and perception evaluator.
     * See [ReasoningSettings.availableTools] for why this is a provider and not
     * a set — pass `{ effectiveTools }`, never a snapshot of it.
     */
    var availableTools: () -> Set<Tool<*>> = { emptySet() }
    var executor: Executor? = null
    var taskFactory: TaskFactory = DefaultTaskFactory

    /**
     * How the Perceive phase renders the state into its prompt (AMPR-403).
     * Leaving it null uses [defaultPerceptionContext].
     *
     * See [ReasoningSettings.perceptionContextBuilder].
     */
    var perceptionContextBuilder: ((AgentState) -> String)? = null

    /**
     * Episodic memory the tool-execution engine records into (AMPR-406). Set it to put this
     * reasoning unit on the persisting path; leave it null and tool outcomes are not stored.
     */
    var outcomeRepository: OutcomeMemoryRepository? = null

    private val parameterStrategies = mutableMapOf<String, ParameterStrategy>()
    private var userGrantProvider: suspend (PlugManifest) -> UserGrants = { UserGrants() }
    private var mcpServerManager: ServerManager? = null

    /**
     * Configure execution settings (parameter strategies, the user-grant provider, and
     * the MCP route). The only surviving per-phase DSL after AMPR-163 Task 11.
     */
    fun execution(configure: ExecutionSettingsBuilder.() -> Unit) {
        val builder = ExecutionSettingsBuilder()
        builder.configure()
        parameterStrategies.putAll(builder.strategies)
        userGrantProvider = builder.userGrantProvider
        mcpServerManager = builder.mcpServerManager
    }

    fun build(): ReasoningSettings = ReasoningSettings(
        executorId = executorId,
        agentRole = agentRole,
        availableTools = availableTools,
        executor = executor,
        taskFactory = taskFactory,
        parameterStrategies = parameterStrategies.toMap(),
        userGrantProvider = userGrantProvider,
        perceptionContextBuilder = perceptionContextBuilder,
        outcomeRepository = outcomeRepository,
        mcpServerManager = mcpServerManager,
    )
}

class ExecutionSettingsBuilder {
    internal val strategies = mutableMapOf<String, ParameterStrategy>()
    internal var userGrantProvider: suspend (PlugManifest) -> UserGrants = { UserGrants() }
    internal var mcpServerManager: ServerManager? = null

    fun registerStrategy(toolId: String, strategy: ParameterStrategy) {
        strategies[toolId] = strategy
    }

    fun userGrants(provider: suspend (PlugManifest) -> UserGrants) {
        userGrantProvider = provider
    }

    /**
     * The route this unit's MCP tool calls take (AMPR-401). Pass a
     * [PlugContext.mcpServerManager][link.socket.ampere.plug.PlugContext.mcpServerManager]
     * to let the agent call that plug's MCP tools; the permission gate still runs inside
     * [ToolExecutionEngine] before the route is used.
     */
    fun mcpServers(manager: ServerManager?) {
        mcpServerManager = manager
    }
}
