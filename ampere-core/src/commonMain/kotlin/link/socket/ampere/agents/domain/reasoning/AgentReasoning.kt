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
 * knowledge extraction.
 *
 * Usage:
 * ```kotlin
 * val reasoning = AgentReasoning.create(config, executorId, eventApi, activePromptProvider) {
 *     agentRole = "Spark-Based Agent (ANALYTICAL)"
 *     availableTools = requiredTools
 *
 *     execution {
 *         registerStrategy("create_issues", ProjectParams.IssueCreation(...))
 *     }
 * }
 *
 * val idea = reasoning.evaluatePerception(perception)
 * val plan = reasoning.generatePlan(task, ideas)
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
    private val planExecutor = PlanExecutor(settings.executorId)

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
        ).also { engine ->
            settings.parameterStrategies.forEach { (toolId, strategy) ->
                engine.registerStrategy(toolId, strategy)
            }
        }
    } else {
        null
    }

    // ========================================================================
    // Perception
    // ========================================================================

    /**
     * Evaluates a perception and generates insights.
     */
    suspend fun <S : AgentState> evaluatePerception(perception: Perception<S>): Idea {
        // Use mock response if available
        mockResponses?.perceptionEvaluator?.let { evaluator ->
            @Suppress("UNCHECKED_CAST")
            return evaluator(perception as Perception<AgentState>)
        }

        return perceptionEvaluator?.evaluate(
            perception = perception,
            contextBuilder = { state -> "State: $state" },
            agentRole = settings.agentRole,
            availableTools = settings.availableTools,
            runId = runId,
        ) ?: throw IllegalStateException("No perception evaluator configured")
    }

    // ========================================================================
    // Planning
    // ========================================================================

    /**
     * Generates a plan for accomplishing a task.
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
            availableTools = settings.availableTools,
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
     */
    suspend fun executePlan(
        plan: Plan,
        stepExecutor: suspend (Task, StepContext) -> StepResult,
    ): PlanExecutionResult {
        return planExecutor.execute(plan, stepExecutor)
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
                message = "Tool execution engine not configured",
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
                availableTools = emptySet(),
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
 */
data class ReasoningSettings(
    val executorId: ExecutorId,
    val agentRole: String,
    val availableTools: Set<Tool<*>>,
    val executor: Executor?,
    val taskFactory: TaskFactory,
    val parameterStrategies: Map<String, ParameterStrategy>,
    val userGrantProvider: suspend (PlugManifest) -> UserGrants,
)

/**
 * Builder for [ReasoningSettings].
 *
 * The DSL is intentionally narrow after AMPR-163 Task 11. Only
 * [execution] survives — it registers parameter strategies and the
 * user-grant provider. Per-phase prompt/context customisation has moved
 * to the `.spark.md` artifacts the agent stacks at construction time.
 */
class ReasoningSettingsBuilder(private val executorId: ExecutorId) {
    var agentRole: String = "Agent"
    var availableTools: Set<Tool<*>> = emptySet()
    var executor: Executor? = null
    var taskFactory: TaskFactory = DefaultTaskFactory

    private val parameterStrategies = mutableMapOf<String, ParameterStrategy>()
    private var userGrantProvider: suspend (PlugManifest) -> UserGrants = { UserGrants() }

    /**
     * Configure execution settings (parameter strategies + user-grant
     * provider). The only surviving per-phase DSL after AMPR-163 Task 11.
     */
    fun execution(configure: ExecutionSettingsBuilder.() -> Unit) {
        val builder = ExecutionSettingsBuilder()
        builder.configure()
        parameterStrategies.putAll(builder.strategies)
        userGrantProvider = builder.userGrantProvider
    }

    fun build(): ReasoningSettings = ReasoningSettings(
        executorId = executorId,
        agentRole = agentRole,
        availableTools = availableTools,
        executor = executor,
        taskFactory = taskFactory,
        parameterStrategies = parameterStrategies.toMap(),
        userGrantProvider = userGrantProvider,
    )
}

class ExecutionSettingsBuilder {
    internal val strategies = mutableMapOf<String, ParameterStrategy>()
    internal var userGrantProvider: suspend (PlugManifest) -> UserGrants = { UserGrants() }

    fun registerStrategy(toolId: String, strategy: ParameterStrategy) {
        strategies[toolId] = strategy
    }

    fun userGrants(provider: suspend (PlugManifest) -> UserGrants) {
        userGrantProvider = provider
    }
}
