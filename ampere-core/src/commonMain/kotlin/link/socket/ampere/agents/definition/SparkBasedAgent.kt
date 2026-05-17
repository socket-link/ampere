package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.product.ProductState
import link.socket.ampere.agents.definition.project.ProjectState
import link.socket.ampere.agents.definition.qa.QualityState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.cognition.sparks.RoleSpark
import link.socket.ampere.agents.domain.cognition.sparks.SparkRegistry
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.planning.ToolPlanSteps
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.util.ioDispatcher
import link.socket.ampere.util.runBlockingCompat

/**
 * A concrete agent implementation using the Spark-based cognitive differentiation system.
 *
 * SparkBasedAgent is a general-purpose agent that derives its behavior from
 * its CognitiveAffinity and accumulated Sparks rather than from hardcoded
 * class implementations. This enables flexible, observable cognitive contexts
 * without requiring separate agent classes for each specialization.
 *
 * The system prompt is dynamically built from the SparkStack before each LLM
 * interaction, and tool/file access is computed from Spark constraints.
 *
 * Parameterized over [S] so role-specific factories (e.g.
 * `SparkBasedAgent<CodeState>`, `SparkBasedAgent<ProductState>`) can carry
 * domain-specific state without subclassing for behavior.
 *
 * @param agentId Unique identifier for this agent
 * @param cognitiveAffinity The cognitive affinity that shapes how this agent thinks
 * @param initialState The starting state for this agent
 * @param _eventApi Optional event API for observability
 * @param _memoryService Optional memory service for knowledge persistence
 * @param _aiConfiguration Optional AI configuration (uses default if not provided)
 */
@Serializable
open class SparkBasedAgent<S : AgentState>(
    private val agentId: AgentId,
    private val cognitiveAffinity: CognitiveAffinity,
    override val initialState: S,
    @Transient
    private val _additionalTools: Set<Tool<*>> = emptySet(),
    @Transient
    private val _eventApi: AgentEventApi? = null,
    @Transient
    private val _memoryService: AgentMemoryService? = null,
    @Transient
    private val _aiConfiguration: AIConfiguration? = null,
    @Transient
    private val _llmProvider: LlmProvider? = null,
    @Transient
    private val _observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    @Transient
    private val _reasoningOverride: AgentReasoning? = null,
) : ObservableAgent<S>(_eventApi, _observabilityScope) {

    @Transient
    private var _phaseSparkLibrary: PhaseSparkLibrary? = null

    /**
     * Sets the [PhaseSparkLibrary] consulted by the agent's [PhaseSparkManager]
     * when [link.socket.ampere.agents.domain.cognition.sparks.AmpereSpikeFlags.declarativeSparksEnabled]
     * is on. Must be called before the agent enters its first cognitive phase
     * (the manager is constructed lazily on first phase entry).
     */
    internal fun setPhaseSparkLibrary(library: PhaseSparkLibrary?) {
        _phaseSparkLibrary = library
    }

    override fun createPhaseSparkManager(): PhaseSparkManager<S> =
        PhaseSparkManager.createWithLibrary(
            agent = this,
            phaseConfig = agentConfiguration.cognitiveConfig.phaseSparks,
            library = _phaseSparkLibrary,
        )

    override val id: AgentId = agentId

    override val affinity: CognitiveAffinity = cognitiveAffinity

    @Transient
    override val memoryService: AgentMemoryService? = _memoryService

    /**
     * Every Spark-based agent ships with [ToolPlanSteps] by default so that
     * the JSON shape of a structured plan lives with the tool that produces
     * it rather than being baked into any per-agent profile. Factories layer
     * additional domain tools on top via the [_additionalTools] constructor
     * parameter.
     */
    @Transient
    override val requiredTools: Set<Tool<*>> = setOf(ToolPlanSteps()) + _additionalTools

    private val effectiveAiConfiguration: AIConfiguration
        get() = _aiConfiguration ?: AIConfigurationFactory.getDefaultConfiguration()

    override val agentConfiguration: AgentConfiguration
        get() = AgentConfiguration(
            agentDefinition = AgentDefinition.Custom(
                name = "SparkBasedAgent-$id",
                description = "A Spark-based agent with affinity: ${affinity.name}",
                prompt = currentSystemPrompt,
            ),
            aiConfiguration = effectiveAiConfiguration,
            llmProvider = _llmProvider,
        )

    // Initialize the SparkStack with the configured affinity
    init {
        reinitializeSparkStack()
    }

    // ========================================================================
    // Reasoning Infrastructure
    // ========================================================================

    private val reasoning: AgentReasoning by lazy {
        _reasoningOverride ?: AgentReasoning.create(
            config = agentConfiguration,
            executorId = id,
            eventApi = _eventApi,
            activePromptProvider = { currentSystemPrompt },
        ) {
            agentRole = "Spark-Based Agent (${affinity.name})"
            availableTools = requiredTools
        }
    }

    // ========================================================================
    // Neural Agent Implementation
    // ========================================================================

    override val runLLMToEvaluatePerception: (Perception<S>) -> Idea = { perception ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                reasoning.evaluatePerception(perception)
            }
        }
    }

    override val runLLMToPlan: (Task, List<Idea>) -> Plan = { task, ideas ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                reasoning.generatePlan(task, ideas)
            }
        }
    }

    override val runLLMToExecuteTask: (Task) -> Outcome = { task ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                val plan = reasoning.generatePlan(task, emptyList())
                reasoning.executePlan(plan) { step, _ ->
                    executePlanStep(step, parentTask = task)
                }.outcome
            }
        }
    }

    /**
     * Routes a plan step to its nominated tool. Strict tool-id dispatch with no
     * keyword fallback — if [Task.CodeChange.toolId] is missing or doesn't
     * match a tool in [requiredTools], the step fails fast with a clear error.
     *
     * Steps with `toolId == null` are treated as pure reasoning placeholders
     * and succeed without invoking anything (the LLM was asked to mark
     * tool-less steps with `toolToUse = null` in the plan_steps schema).
     */
    private suspend fun executePlanStep(step: Task, parentTask: Task): StepResult {
        if (step is Task.Blank) {
            return StepResult.success(
                description = "blank step",
                details = "no-op",
            )
        }
        if (step !is Task.CodeChange) {
            return StepResult.failure(
                description = step.id,
                error = "Plan step ${step.id} is of unsupported type " +
                    "${step::class.simpleName}; spark-based execution only " +
                    "handles Task.CodeChange steps emitted by plan_steps.",
                isCritical = true,
            )
        }

        val toolId = step.toolId
        if (toolId == null) {
            return StepResult.success(
                description = step.description,
                details = "reasoning step (no toolToUse)",
            )
        }

        val tool = requiredTools.firstOrNull { it.id == toolId }
            ?: return StepResult.failure(
                description = step.description,
                error = "Plan step ${step.id} nominated toolToUse=\"$toolId\", " +
                    "which is not in the agent's required tools " +
                    "(${requiredTools.joinToString { it.id }}). The executor " +
                    "routes strictly by tool id — no keyword fallback.",
                isCritical = true,
            )

        val request = buildPlanStepRequest(step, parentTask)
        return when (val outcome = reasoning.executeTool(tool, request)) {
            is ExecutionOutcome.Success -> StepResult.success(
                description = step.description,
                details = "tool=$toolId outcome=${outcome::class.simpleName}",
            )
            is ExecutionOutcome.Failure -> StepResult.failure(
                description = step.description,
                error = "tool=$toolId failed: ${outcome::class.simpleName}",
                isCritical = true,
            )
            else -> StepResult.success(
                description = step.description,
                details = "tool=$toolId outcome=${outcome::class.simpleName}",
            )
        }
    }

    /**
     * Builds the initial request handed to the tool's parameter strategy. The
     * strategy enriches this with a tool-specific context (e.g. promotes the
     * generic [ExecutionContext.NoChanges] wrapper to
     * [ExecutionContext.GitOperation] when invoking a git tool); when no
     * strategy is registered the tool must be able to act on the raw request.
     */
    private fun buildPlanStepRequest(step: Task.CodeChange, parentTask: Task): ExecutionRequest<*> {
        val ticket = link.socket.ampere.agents.events.tickets.Ticket(
            id = "spark-task-${parentTask.id}",
            title = parentTask.id,
            description = step.description,
            type = link.socket.ampere.agents.events.tickets.TicketType.TASK,
            priority = link.socket.ampere.agents.events.tickets.TicketPriority.LOW,
            status = link.socket.ampere.agents.domain.status.TicketStatus.InProgress,
            assignedAgentId = id,
            createdByAgentId = id,
            createdAt = kotlinx.datetime.Clock.System.now(),
            updatedAt = kotlinx.datetime.Clock.System.now(),
        )
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = id,
                ticket = ticket,
                task = step,
                instructions = step.description,
            ),
            constraints = link.socket.ampere.agents.execution.request.ExecutionConstraints(),
        )
    }

    override val runLLMToExecuteTool: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome = { tool, request ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                reasoning.executeTool(tool, request)
            }
        }
    }

    override val runLLMToEvaluateOutcomes: (List<Outcome>) -> Idea = { outcomes ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                reasoning.evaluateOutcomes(outcomes, memoryService).summaryIdea
            }
        }
    }

    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge = reasoning.extractKnowledge(outcome, task, plan)

    override fun callLLM(prompt: String): String = runBlockingCompat(ioDispatcher) {
        withTimeout(60000) {
            reasoning.callLLM(prompt)
        }
    }

    companion object {

        /**
         * Canonical id of the bundled role spark fixture
         * (`role-code.spark.md`) that supplies the Code agent's role-level
         * guidance and capability constraints. Looked up against the
         * [PhaseSparkLibrary] handed to the `Code` / `Quality` factories.
         */
        const val ROLE_CODE_SPARK_ID: String = "code"

        /**
         * Resource id of the bundled declarative spark that supplies the
         * Code agent's per-phase guidance. Activated during phase entry
         * when a `PhaseSparkLibrary` containing it has been wired into
         * the agent.
         */
        const val CODE_AGENT_SPARK_ID: String = "code-agent"

        /**
         * Builds a Code-focused [SparkBasedAgent]: `ANALYTICAL` affinity,
         * the declarative `role-code` spark stacked at construction time,
         * and the `plan_steps` tool already in its toolset.
         *
         * The factory is the supported entry point for a code agent in
         * the spark world. It mirrors the constructor shape of the
         * legacy `CodeAgent` so call sites can swap implementations
         * without restructuring their dependency graph.
         *
         * Since AMPR-165 the role spark is resolved from
         * [phaseSparkLibrary] by canonical id ([ROLE_CODE_SPARK_ID])
         * rather than referenced as a compile-time singleton. Construction
         * fails fast if the library has no matching fixture — there is no
         * silent fallback to the old `RoleSpark.Code` object.
         *
         * The declarative `code-agent.spark.md` per-phase guidance is
         * **not** applied here. That is the responsibility of the
         * surrounding `AgentFactory` (or test harness), which wires the
         * same `PhaseSparkLibrary` via the agent's internal setter before
         * the first cognitive phase entry.
         *
         * @param tools additional tools layered on top of the default
         *   `plan_steps` tool (typically a code-writing tool plus the
         *   git tool set). Tool-owned parameter strategies, if any,
         *   travel with the tools themselves.
         * @param phaseSparkLibrary library that must contain the
         *   `role-code` fixture; construction fails fast otherwise.
         */
        fun Code(
            sparkRegistry: SparkRegistry,
            agentId: AgentId = generateUUID("SparkBasedAgent-Code"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
        ): SparkBasedAgent<CodeState> {
            val roleSpark = resolveCodeRoleSpark(sparkRegistry)
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
                initialState = CodeState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
            )
            agent.spark<SparkBasedAgent<CodeState>>(roleSpark)
            return agent
        }

        private fun resolveCodeRoleSpark(registry: SparkRegistry) =
            registry.roleSparkById(ROLE_CODE_SPARK_ID)
                ?: error(
                    "SparkBasedAgent factory requires the declarative role spark " +
                        "'$ROLE_CODE_SPARK_ID' (from files/sparks/role-code.spark.md) " +
                        "in the provided SparkRegistry, but lookup returned null. " +
                        "Use DefaultPhaseSparkLibrary.load() so the bundled role-code " +
                        "fixture is included.",
                )

        /**
         * Resource id of the bundled declarative spark that supplies the
         * Product agent's per-phase guidance.
         */
        const val PRODUCT_AGENT_SPARK_ID: String = "product-agent"

        /**
         * Builds a Product-focused [SparkBasedAgent]: `INTEGRATIVE`
         * affinity, [RoleSpark.Planning] stacked at construction time,
         * and the `plan_steps` tool already in its toolset.
         *
         * Mirrors the legacy `ProductAgent` shape. Declarative
         * `product-agent.spark.md` guidance is wired separately by the
         * surrounding `AgentFactory`.
         */
        fun Product(
            agentId: AgentId = generateUUID("SparkBasedAgent-Product"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
        ): SparkBasedAgent<ProductState> {
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
                initialState = ProductState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
            )
            agent.spark<SparkBasedAgent<ProductState>>(RoleSpark.Planning)
            return agent
        }

        /**
         * Resource id of the bundled declarative spark that supplies the
         * Project agent's per-phase guidance.
         */
        const val PROJECT_AGENT_SPARK_ID: String = "project-agent"

        /**
         * Builds a Project-focused [SparkBasedAgent]: `INTEGRATIVE`
         * affinity, [RoleSpark.Planning] stacked at construction time,
         * and the `plan_steps` tool already in its toolset.
         *
         * Mirrors the legacy `ProjectAgent` shape. Typical tool stack
         * includes the issue-creation tool and the human-escalation
         * tool, each carrying its own `ProjectParams.*` parameter
         * strategy.
         */
        fun Project(
            agentId: AgentId = generateUUID("SparkBasedAgent-Project"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
        ): SparkBasedAgent<ProjectState> {
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
                initialState = ProjectState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
            )
            agent.spark<SparkBasedAgent<ProjectState>>(RoleSpark.Planning)
            return agent
        }

        /**
         * Resource id of the bundled declarative spark that supplies the
         * Quality agent's per-phase guidance.
         */
        const val QUALITY_AGENT_SPARK_ID: String = "quality-agent"

        /**
         * Builds a Quality-focused [SparkBasedAgent]: `ANALYTICAL`
         * affinity, the declarative `role-code` spark stacked at
         * construction time (validation work reads & runs code), and the
         * `plan_steps` tool already in its toolset.
         *
         * Mirrors the legacy `QualityAgent` shape. Per AMPR-165, the role
         * spark is resolved from [phaseSparkLibrary] by canonical id;
         * construction fails fast if it isn't present.
         */
        fun Quality(
            sparkRegistry: SparkRegistry,
            agentId: AgentId = generateUUID("SparkBasedAgent-Quality"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
        ): SparkBasedAgent<QualityState> {
            val roleSpark = resolveCodeRoleSpark(sparkRegistry)
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
                initialState = QualityState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
            )
            agent.spark<SparkBasedAgent<QualityState>>(roleSpark)
            return agent
        }
    }
}
