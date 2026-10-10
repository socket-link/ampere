package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.product.ProductState
import link.socket.ampere.agents.definition.project.ProjectState
import link.socket.ampere.agents.definition.qa.QualityState
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.cognition.sparks.SparkRegistry
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.describeResult
import link.socket.ampere.agents.execution.detailText
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.planning.ToolPlanSteps
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.UserGrants
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
 * @param _cognitiveConfig Cognitive-loop configuration, notably the phase-spark switches
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
    /**
     * Outbound LLM transport (AMPR-236). Null declares no transport: the
     * agent's first LLM call throws
     * [MissingUpstreamLlmClientException][link.socket.ampere.llm.MissingUpstreamLlmClientException]
     * rather than falling back to the direct-provider call. Pass
     * [BundledUpstreamLlmClient][link.socket.ampere.llm.BundledUpstreamLlmClient]
     * to opt into that fallback.
     */
    @Transient
    private val _upstreamLlmClient: UpstreamLlmClient? = null,
    /**
     * Outbound decision transport (AMPR-384). Null declares none: the agent's
     * first `decide` throws
     * [MissingUpstreamDecisionClientException][link.socket.ampere.llm.decide.MissingUpstreamDecisionClientException].
     * Nothing in the loop calls `decide` yet (W1 is shadow only).
     */
    @Transient
    private val _upstreamDecisionClient: UpstreamDecisionClient? = null,
    @Transient
    private val _observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    @Transient
    private val _reasoningOverride: AgentReasoning? = null,
    /**
     * Provider-agnostic routing seam (AMPR-219). When set, the agent's
     * [AgentConfiguration.cognitiveRelay] is populated so every LLM call routes
     * through the relay's capability/cost-aware selection rather than using the
     * static [AIConfiguration] directly. Null keeps the pre-activation behavior
     * (no relay), which is the default for callers that don't opt in.
     */
    @Transient
    private val _cognitiveRelay: CognitiveRelay? = null,
    /**
     * Per-agent capability-rung floor (AMPR-219). Threaded onto the agent's
     * [AgentDefinition] so [AgentLLMService][link.socket.ampere.agents.domain.reasoning.AgentLLMService]
     * merges it into the [RoutingContext][link.socket.ampere.agents.domain.routing.RoutingContext]
     * and the relay refuses to route below it. Null declares no floor.
     */
    @Transient
    private val _minimumRung: CapabilityRung? = null,
    /**
     * Tool executor seam (AMPR-186). When set, tool calls dispatch through this
     * [Executor] (e.g. a `NoOpExecutor` for effect-free eval Bench runs) instead
     * of leaving the tool-execution engine unconfigured. Null preserves the
     * pre-existing behavior (no executor, tool calls fail as "not configured").
     */
    @Transient
    private val _executor: Executor? = null,
    /**
     * Ambient Arc-run identity (AMPR-240). Threaded into [AgentReasoning] so
     * every `RoutingContext` this agent builds carries it as `workflowId`,
     * reaching `ProviderCallStartedEvent`/`ProviderCallCompletedEvent` and
     * therefore `ArcTraceProjection`. Null preserves pre-existing behavior
     * (no run correlation) for callers that don't construct through an Arc.
     */
    @Transient
    private val _runId: RunId? = null,
    /**
     * Persisted-grant source for [link.socket.ampere.plug.permission.PlugPermissionGate]
     * (AMPR-348). When set, plug tools this agent dispatches are gated against
     * the caller's real grants instead of [ExecutionSettingsBuilder]'s
     * deny-all default. Null preserves the pre-existing behavior — every
     * plug tool with `requiredPermissions` is denied — which stays correct
     * for callers with no grant store (tests, headless use).
     */
    @Transient
    private val _userGrantProvider: (suspend (PlugManifest) -> UserGrants)? = null,
    /**
     * The workspace this agent's file operations are confined to (AMPR-300).
     * Stamped onto every plan-step [ExecutionRequest] this agent builds, so
     * a code tool's parameter strategy can promote the request into an
     * [ExecutionContext.Code] rooted here. Null means the agent was built
     * without a workspace: code-file tools then refuse to dispatch with a
     * typed failure instead of writing into the process working directory.
     * The production factories ([AgentFactory], [SparkAgentFactory]) require
     * a workspace, so null is reachable only from hand-built agents.
     */
    @Transient
    private val _workspace: ExecutionWorkspace? = null,
    /**
     * Cognitive-loop configuration (AMPR-387). Reaches [agentConfiguration], which
     * is what [createPhaseSparkManager] reads, so a caller that sets
     * `phaseSparks.enabled = true` actually gets phase handling — before AMPR-387
     * this agent built its configuration without one and the manager always saw the
     * default (phases off). Defaults to [CognitiveConfig], i.e. phases off, so every
     * caller that does not opt in is unaffected.
     */
    @Transient
    private val _cognitiveConfig: CognitiveConfig = CognitiveConfig(),
    /**
     * Episodic memory this agent's tool calls are recorded into (AMPR-406). Threaded into
     * [AgentReasoning] and on into its
     * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine], which
     * is the persisting path for every `ExecutionOutcome` the agent's plan steps produce.
     * Null leaves them unrecorded, which is what an agent built without a database gets.
     *
     * Note that an agent with no [_executor] builds no engine at all, so a store without an
     * executor beside it records nothing — not because the wiring is wrong, but because no
     * tool ever dispatches.
     */
    @Transient
    private val _outcomeRepository: OutcomeMemoryRepository? = null,
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
            eventApi = _eventApi,
        )

    override val id: AgentId = agentId

    override val affinity: CognitiveAffinity = cognitiveAffinity

    /**
     * The Arc run this agent was built for (AMPR-240), surfaced so every event it publishes
     * carries the run on its envelope (F4, AMPR-386) — phase brackets through
     * [PhaseSparkManager], spark events and snapshots through [ObservableAgent], and memory
     * recall through [AgentMemoryService].
     */
    @Transient
    override val currentRunId: RunId? = _runId

    /**
     * The workspace this agent is pinned to (AMPR-300), or null when it was
     * built without one. See the `_workspace` constructor parameter.
     */
    val workspace: ExecutionWorkspace?
        get() = _workspace

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
                minimumRung = _minimumRung,
            ),
            aiConfiguration = effectiveAiConfiguration,
            cognitiveConfig = _cognitiveConfig,
            llmProvider = _llmProvider,
            cognitiveRelay = _cognitiveRelay,
            upstreamLlmClient = _upstreamLlmClient,
            upstreamDecisionClient = _upstreamDecisionClient,
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
            runId = _runId,
        ) {
            agentRole = "Spark-Based Agent (${affinity.name})"
            // AMPR-400: the narrowed set, read live on every call — not
            // `requiredTools`, which ignores what the spark stack permits.
            availableTools = { effectiveTools }
            executor = _executor
            outcomeRepository = _outcomeRepository
            _userGrantProvider?.let { provider ->
                execution { userGrants(provider) }
            }
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

    // `@Transient` because this class is `@Serializable` and the plugin resolves a serializer
    // for every type argument of a property's function type. The other `runLLM*` lambdas get
    // away without it only because theirs happen to be serializable; `KnowledgeWithScore` is a
    // plain data class. A lambda is behaviour, not state, so none of them belong in a serial
    // form anyway.
    @Transient
    override val runLLMToPlan: (Task, List<Idea>, List<KnowledgeWithScore>) -> Plan =
        { task, ideas, relevantKnowledge ->
            runBlockingCompat(ioDispatcher) {
                withTimeout(60000) {
                    // AMPR-388: knowledge Recall found reaches `PlanGenerator`'s prompt. This
                    // used to call `generatePlan(task, ideas)` and take the empty default.
                    reasoning.generatePlan(task, ideas, relevantKnowledge)
                }
            }
        }

    /**
     * Dispatches [task] as the one plan step it is: no LLM call of its own.
     *
     * [AutonomousAgent.executePlan] has already walked the Plan phase's output
     * and handed this one step over, and the step names the tool to run in its
     * [Task.CodeChange.toolId]. Until AMPR-396 this re-entered planning —
     * `generatePlan(task, emptyList())` — and dispatched *that* sub-plan's steps
     * instead, so an N-step plan spent N extra PLAN calls and never ran the tool
     * any of its own steps nominated. Re-planning a step into a sub-plan is a
     * cycle a host asks for by name ([runSubPlanForTask]), not what executing a
     * step means.
     *
     * [priorResults] is what the earlier steps of the same plan produced, handed
     * down by [AutonomousAgent.executePlan] (AMPR-408). It rides on the request
     * this step dispatches, which is how the nominated tool's parameter prompt
     * gets to see it.
     */
    override val runLLMToExecuteTask: (Task, List<StepOutcome>) -> Outcome = { task, priorResults ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                dispatchStepAsPlan(task, priorResults)
            }
        }
    }

    /**
     * Re-plans [task] into a sub-plan and dispatches that plan's steps — the
     * opt-in sub-cycle, costing one PLAN call plus one dispatch per generated
     * step.
     *
     * Nothing in the PROPEL loop calls this: [AutonomousAgent.executePlan]
     * dispatches the steps the Plan phase already produced, one call each. A
     * host reaches for it when a step it holds is too coarse to dispatch
     * directly and it wants the agent to break that step down first.
     *
     * The sub-plan is a Plan generation like any other, so Recall precedes it
     * (AMPR-388). It recalls for [task] rather than being handed knowledge: the
     * entry point takes a `Task` and nothing else, so there is no outer plan to
     * inherit from by construction, and recalling here scopes the knowledge to
     * the step being broken down. With no memory service wired this is a no-op
     * returning an empty list, and the prompt is then byte-identical to the one
     * `generatePlan(task, emptyList())` built.
     */
    fun runSubPlanForTask(task: Task): Outcome =
        runBlockingCompat(ioDispatcher) {
            withTimeout(60000) {
                val relevantKnowledge = recallRelevantKnowledgeForTask(task)
                val plan = reasoning.generatePlan(task, emptyList(), relevantKnowledge)
                // Nothing ran before this sub-plan: the entry point takes a Task and
                // nothing else, so the chain starts here and grows step by step.
                reasoning.executePlan(plan, priorResults = emptyList()) { step, context ->
                    executePlanStep(step, parentTask = task, priorResults = context.priorResults)
                }.outcome
            }
        }

    /**
     * Runs [step] through [executePlanStep] and folds its [StepResult] into an
     * [Outcome].
     *
     * The fold goes through [AgentReasoning.executePlan] with a one-step plan
     * rather than a bespoke `StepResult` → `Outcome` conversion, so a single
     * step and a whole plan settle to the same outcome shape from the same code.
     * [AgentReasoning.executePlan] makes no model call.
     *
     * [priorResults] is what ran before this step in the plan it came from, seeded
     * into the wrapper plan's step context (AMPR-408).
     */
    private suspend fun dispatchStepAsPlan(step: Task, priorResults: List<StepOutcome>): Outcome {
        val result = reasoning.executePlan(
            plan = Plan.ForTask(
                task = step,
                tasks = listOf(step),
                estimatedComplexity = 1,
            ),
            // The one-step plan is a wrapper, not the whole plan, so the chain the
            // real plan has accumulated is seeded in rather than starting empty
            // (AMPR-408). `context.priorResults` is then the one place a step
            // executor reads it, whichever path got here.
            priorResults = priorResults,
        ) { planStep, context ->
            executePlanStep(planStep, parentTask = step, priorResults = context.priorResults)
        }
        return result.outcome.reportingStep(result.stepOutcomes.singleOrNull())
    }

    /**
     * This outcome, carrying the step's own report as its message (AMPR-408).
     *
     * [AgentReasoning.executePlan] summarises a plan for its caller, and the summary of a
     * plan with one step in it is a tally of one — `"✓ Success: 1"`. That message is the
     * only thing [AutonomousAgent.executePlan] can hand the *next* step, so on the live
     * path, where every step is dispatched as its own one-step plan (AMPR-396), a tally is
     * all a later step would ever learn about an earlier one. The verdict still comes from
     * the plan's own outcome — which is what the loop, the Arc and `rememberNewOutcome`
     * read — only the message is the step's.
     *
     * A multi-step plan ([runSubPlanForTask]) keeps the summary: its steps read each other
     * through `StepContext.priorResults` and never through this message.
     */
    private fun Outcome.reportingStep(stepOutcome: StepOutcome?): Outcome {
        val report = stepOutcome?.detailText()?.takeIf { it.isNotBlank() } ?: return this
        return when (this) {
            is ExecutionOutcome.NoChanges.Success -> copy(message = report)
            is ExecutionOutcome.NoChanges.Failure -> copy(message = report)
            else -> this
        }
    }

    /**
     * Routes a plan step to its nominated tool. Strict tool-id dispatch with no
     * keyword fallback — if [Task.CodeChange.toolId] is missing or doesn't
     * match a tool in [effectiveTools], the step fails fast with a clear error.
     *
     * Dispatch is against [effectiveTools], the same narrowed set the planner
     * was offered, so a tool the spark stack withdrew is as unreachable as one
     * the agent was never built with — one set, one error, no second path
     * (AMPR-400).
     *
     * Steps with `toolId == null` are treated as pure reasoning placeholders
     * and succeed without invoking anything (the LLM was asked to mark
     * tool-less steps with `toolToUse = null` in the plan_steps schema).
     */
    private suspend fun executePlanStep(
        step: Task,
        parentTask: Task,
        priorResults: List<StepOutcome>,
    ): StepResult {
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

        val dispatchable = effectiveTools
        val tool = dispatchable.firstOrNull { it.id == toolId }
            ?: return StepResult.failure(
                description = step.description,
                error = "Plan step ${step.id} nominated toolToUse=\"$toolId\", " +
                    "which is not in the agent's effective tool set " +
                    "(${dispatchable.joinToString { it.id }}) — the tools it was " +
                    "built with, narrowed by its spark stack. The executor " +
                    "routes strictly by tool id — no keyword fallback.",
                isCritical = true,
            )

        val request = buildPlanStepRequest(step, parentTask, priorResults)
        return when (val outcome = reasoning.executeTool(tool, request)) {
            is ExecutionOutcome.Success -> StepResult.success(
                description = step.description,
                details = outcome.stepDetails(toolId),
            )
            is ExecutionOutcome.Failure -> StepResult.failure(
                description = step.description,
                error = "tool=$toolId failed: ${outcome::class.simpleName}; " +
                    outcome.describeResult(),
                isCritical = true,
            )
            else -> StepResult.success(
                description = step.description,
                details = outcome.stepDetails(toolId),
            )
        }
    }

    /**
     * What the step reports having done: the tool it ran, the outcome variant, and
     * what that outcome actually said.
     *
     * The last part is the AMPR-408 half. This read `"tool=$toolId
     * outcome=Success"` and nothing more, so even once the results were threaded
     * forward the next step would have been handed a type name — true, and of no
     * use to a model asked to summarise what the previous step found.
     */
    private fun ExecutionOutcome.stepDetails(toolId: String): String {
        val result = describeResult()
        val head = "tool=$toolId outcome=${this::class.simpleName}"
        return if (result.isBlank()) head else "$head; $result"
    }

    /**
     * Builds the initial request handed to the tool's parameter strategy. The
     * strategy enriches this with a tool-specific context (e.g. promotes the
     * generic [ExecutionContext.NoChanges] wrapper to
     * [ExecutionContext.GitOperation] when invoking a git tool); when no
     * strategy is registered the tool must be able to act on the raw request.
     * The agent's pinned workspace rides along on the request (AMPR-300) so a
     * strategy promoting into [ExecutionContext.Code] has a root to use, and so do
     * the earlier steps' results (AMPR-408) so a strategy can parameterise this step
     * from them.
     */
    private fun buildPlanStepRequest(
        step: Task.CodeChange,
        parentTask: Task,
        priorResults: List<StepOutcome>,
    ): ExecutionRequest<*> {
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
            // AMPR-300: the pin a code tool's strategy roots its Code context in.
            workspace = _workspace,
            // AMPR-414: what the spark stack permits this agent to touch, read
            // live here for the same reason `effectiveTools` is — the stack is
            // mutable for the agent's lifetime, so a scope captured at
            // construction would outlive the narrowing that produced it.
            fileAccessScope = effectiveFileAccess,
            // AMPR-408: what the earlier steps of this plan produced. The strategy
            // that generates this step's parameters renders them into its prompt,
            // which is the only way a step can be parameterised from an earlier
            // step's result.
            priorResults = priorResults,
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

    override fun callLLM(prompt: String): String = callLLM(prompt, requirements = null)

    /**
     * Calls the LLM with a per-call capability requirement (AMPR-232).
     *
     * The requirement's [CapabilityRequirement.minRung] composes with the agent's
     * declared [_minimumRung] as the stricter of the two, so a call site can raise
     * the floor for one call without ever lowering the agent's own.
     */
    fun callLLM(
        prompt: String,
        requirements: CapabilityRequirement?,
    ): String = runBlockingCompat(ioDispatcher) {
        withTimeout(60000) {
            reasoning.callLLM(prompt, requirements = requirements)
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
         * Canonical id of the bundled planning role spark fixture
         * (`role-planning.spark.md`) used by Product and Project agents.
         */
        const val ROLE_PLANNING_SPARK_ID: String = "planning"

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
         * [sparkRegistry] by canonical id ([ROLE_CODE_SPARK_ID])
         * rather than referenced as a compile-time singleton. Construction
         * fails fast if the library has no matching fixture — there is no
         * silent fallback to a Kotlin role singleton.
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
         * @param sparkRegistry registry that must contain the
         *   `role-code` fixture; construction fails fast otherwise.
         * @param workspace the directory this agent's code tools are confined
         *   to (AMPR-300). Null builds an unpinned agent whose code-file tools
         *   refuse to dispatch; the production factories always supply one.
         * @param cognitiveConfig cognitive-loop configuration (AMPR-387); its
         *   `phaseSparks` block is what the agent's `PhaseSparkManager` is built
         *   from. Default leaves phase handling off, as before.
         */
        fun Code(
            sparkRegistry: SparkRegistry,
            agentId: AgentId = generateUUID("SparkBasedAgent-Code"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            upstreamLlmClient: UpstreamLlmClient? = null,
            upstreamDecisionClient: UpstreamDecisionClient? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
            cognitiveRelay: CognitiveRelay? = null,
            minimumRung: CapabilityRung? = null,
            userGrantProvider: (suspend (PlugManifest) -> UserGrants)? = null,
            workspace: ExecutionWorkspace? = null,
            cognitiveConfig: CognitiveConfig = CognitiveConfig(),
        ): SparkBasedAgent<CodeState> {
            val roleSpark = resolveRequiredRoleSpark(
                registry = sparkRegistry,
                id = ROLE_CODE_SPARK_ID,
                resourceName = "role-code.spark.md",
            )
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
                initialState = CodeState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _upstreamLlmClient = upstreamLlmClient,
                _upstreamDecisionClient = upstreamDecisionClient,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
                _cognitiveRelay = cognitiveRelay,
                _minimumRung = minimumRung,
                _userGrantProvider = userGrantProvider,
                _workspace = workspace,
                _cognitiveConfig = cognitiveConfig,
            )
            agent.spark<SparkBasedAgent<CodeState>>(roleSpark)
            return agent
        }

        private fun resolveRequiredRoleSpark(
            registry: SparkRegistry,
            id: String,
            resourceName: String,
        ) =
            registry.roleSparkById(id)
                ?: error(
                    "SparkBasedAgent factory requires the declarative role spark " +
                        "'$id' (from files/sparks/$resourceName) " +
                        "in the provided SparkRegistry, but lookup returned null. " +
                        "Use DefaultPhaseSparkLibrary.load() so the bundled role " +
                        "fixture is included.",
                )

        /**
         * Resource id of the bundled declarative spark that supplies the
         * Product agent's per-phase guidance.
         */
        const val PRODUCT_AGENT_SPARK_ID: String = "product-agent"

        /**
         * Builds a Product-focused [SparkBasedAgent]: `INTEGRATIVE`
         * affinity, the declarative `role-planning` spark stacked at construction time,
         * and the `plan_steps` tool already in its toolset.
         *
         * Mirrors the legacy `ProductAgent` shape. Declarative
         * `product-agent.spark.md` guidance is wired separately by the
         * surrounding `AgentFactory`.
         */
        fun Product(
            sparkRegistry: SparkRegistry,
            agentId: AgentId = generateUUID("SparkBasedAgent-Product"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            upstreamLlmClient: UpstreamLlmClient? = null,
            upstreamDecisionClient: UpstreamDecisionClient? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
            userGrantProvider: (suspend (PlugManifest) -> UserGrants)? = null,
            workspace: ExecutionWorkspace? = null,
            cognitiveConfig: CognitiveConfig = CognitiveConfig(),
        ): SparkBasedAgent<ProductState> {
            val roleSpark = resolveRequiredRoleSpark(
                registry = sparkRegistry,
                id = ROLE_PLANNING_SPARK_ID,
                resourceName = "role-planning.spark.md",
            )
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
                initialState = ProductState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _upstreamLlmClient = upstreamLlmClient,
                _upstreamDecisionClient = upstreamDecisionClient,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
                _userGrantProvider = userGrantProvider,
                _workspace = workspace,
                _cognitiveConfig = cognitiveConfig,
            )
            agent.spark<SparkBasedAgent<ProductState>>(roleSpark)
            return agent
        }

        /**
         * Resource id of the bundled declarative spark that supplies the
         * Project agent's per-phase guidance.
         */
        const val PROJECT_AGENT_SPARK_ID: String = "project-agent"

        /**
         * Builds a Project-focused [SparkBasedAgent]: `INTEGRATIVE`
         * affinity, the declarative `role-planning` spark stacked at construction time,
         * and the `plan_steps` tool already in its toolset.
         *
         * Mirrors the legacy `ProjectAgent` shape. Typical tool stack
         * includes the issue-creation tool and the human-escalation
         * tool, each carrying its own `ProjectParams.*` parameter
         * strategy.
         */
        fun Project(
            sparkRegistry: SparkRegistry,
            agentId: AgentId = generateUUID("SparkBasedAgent-Project"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            upstreamLlmClient: UpstreamLlmClient? = null,
            upstreamDecisionClient: UpstreamDecisionClient? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
            userGrantProvider: (suspend (PlugManifest) -> UserGrants)? = null,
            workspace: ExecutionWorkspace? = null,
            cognitiveConfig: CognitiveConfig = CognitiveConfig(),
        ): SparkBasedAgent<ProjectState> {
            val roleSpark = resolveRequiredRoleSpark(
                registry = sparkRegistry,
                id = ROLE_PLANNING_SPARK_ID,
                resourceName = "role-planning.spark.md",
            )
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
                initialState = ProjectState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _upstreamLlmClient = upstreamLlmClient,
                _upstreamDecisionClient = upstreamDecisionClient,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
                _userGrantProvider = userGrantProvider,
                _workspace = workspace,
                _cognitiveConfig = cognitiveConfig,
            )
            agent.spark<SparkBasedAgent<ProjectState>>(roleSpark)
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
         * spark is resolved from [sparkRegistry] by canonical id;
         * construction fails fast if it isn't present.
         */
        fun Quality(
            sparkRegistry: SparkRegistry,
            agentId: AgentId = generateUUID("SparkBasedAgent-Quality"),
            aiConfiguration: AIConfiguration? = null,
            eventApi: AgentEventApi? = null,
            memoryService: AgentMemoryService? = null,
            llmProvider: LlmProvider? = null,
            upstreamLlmClient: UpstreamLlmClient? = null,
            upstreamDecisionClient: UpstreamDecisionClient? = null,
            observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            tools: Set<Tool<*>> = emptySet(),
            reasoningOverride: AgentReasoning? = null,
            userGrantProvider: (suspend (PlugManifest) -> UserGrants)? = null,
            workspace: ExecutionWorkspace? = null,
            cognitiveConfig: CognitiveConfig = CognitiveConfig(),
        ): SparkBasedAgent<QualityState> {
            val roleSpark = resolveRequiredRoleSpark(
                registry = sparkRegistry,
                id = ROLE_CODE_SPARK_ID,
                resourceName = "role-code.spark.md",
            )
            val agent = SparkBasedAgent(
                agentId = agentId,
                cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
                initialState = QualityState.blank,
                _additionalTools = tools,
                _eventApi = eventApi,
                _memoryService = memoryService,
                _aiConfiguration = aiConfiguration,
                _llmProvider = llmProvider,
                _upstreamLlmClient = upstreamLlmClient,
                _upstreamDecisionClient = upstreamDecisionClient,
                _observabilityScope = observabilityScope,
                _reasoningOverride = reasoningOverride,
                _userGrantProvider = userGrantProvider,
                _workspace = workspace,
                _cognitiveConfig = cognitiveConfig,
            )
            agent.spark<SparkBasedAgent<QualityState>>(roleSpark)
            return agent
        }
    }
}
