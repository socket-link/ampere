package link.socket.ampere.agents.domain.reasoning

import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.UserGrants

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

        val effectiveRunId = runId ?: outcomes.firstRunIdOrNull()
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
     */
    suspend fun callLLM(
        prompt: String,
        systemMessage: String? = null,
    ): String {
        // Use mock response if available
        mockResponses?.llmCall?.let { call ->
            return call(prompt)
        }

        return llmService?.call(
            prompt = prompt,
            systemMessage = systemMessage ?: "You are a ${settings.agentRole} agent.",
            routingContext = RoutingContext(
                agentId = settings.executorId,
                agentRole = settings.agentRole,
            ),
        ) ?: throw IllegalStateException("No LLM service configured")
    }

    /**
     * Calls the LLM expecting a JSON response.
     */
    suspend fun callLLMForJson(prompt: String): LLMJsonResponse {
        return llmService?.callForJson(
            prompt = prompt,
            routingContext = RoutingContext(
                agentId = settings.executorId,
                agentRole = settings.agentRole,
            ),
        )
            ?: throw IllegalStateException("No LLM service configured")
    }

    companion object {
        /**
         * Creates an AgentReasoning instance with the given configuration.
         */
        fun create(
            config: AgentConfiguration,
            executorId: ExecutorId,
            eventApi: AgentEventApi? = null,
            activePromptProvider: (() -> String?)? = null,
            configure: ReasoningSettingsBuilder.() -> Unit,
        ): AgentReasoning {
            val builder = ReasoningSettingsBuilder(executorId)
            builder.configure()
            return AgentReasoning(
                config = config,
                settings = builder.build(),
                eventApi = eventApi,
                activePromptProvider = activePromptProvider,
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
    val userGrantProvider: suspend (PluginManifest) -> UserGrants,
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
    private var userGrantProvider: suspend (PluginManifest) -> UserGrants = { UserGrants() }

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
    internal var userGrantProvider: suspend (PluginManifest) -> UserGrants = { UserGrants() }

    fun registerStrategy(toolId: String, strategy: ParameterStrategy) {
        strategies[toolId] = strategy
    }

    fun userGrants(provider: suspend (PluginManifest) -> UserGrants) {
        userGrantProvider = provider
    }
}
