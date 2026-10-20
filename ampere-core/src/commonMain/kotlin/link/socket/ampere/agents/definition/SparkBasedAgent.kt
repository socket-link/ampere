package link.socket.ampere.agents.definition

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.ReasoningStepConfig
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.product.ProductState
import link.socket.ampere.agents.definition.project.ProjectState
import link.socket.ampere.agents.definition.qa.QualityState
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.cognition.sparks.SparkRegistry
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.outcome.originOfAll
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
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.detailText
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.priorResultsSection
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.planning.ToolPlanSteps
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Rule
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.UserGrants
import link.socket.ampere.propel.Breaker
import link.socket.ampere.propel.dispatchPlanStep
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
 * This agent runs the PROPEL loop, so none of its calls runs on the device: rung
 * ZERO serves display text only (AMPR-372). Construction refuses a declared floor
 * below [CapabilityRung.ONE] and a configuration that names the on-device provider
 * or model (AMPR-459), and that covers every way one is built — the role factories
 * below, [SparkAgentFactory], [AgentFactory], an Arc step and a hosted seat. A
 * missing floor is legal and keeps the configured model. Words for a person to read
 * go through [OnDeviceInferenceSession][link.socket.ampere.llm.OnDeviceInferenceSession],
 * which is not an agent.
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
     * Only a hosted run's seats are asked anything through it — the run's own questions
     * after an executed cycle (AMPR-421, AMPR-481) and its drift receptor (AMPR-483) —
     * and `RunHost.open` refuses a policy that would ask on a seat carrying none.
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
     * and the relay refuses to route below it. Null declares no floor and keeps the
     * agent on its configured model; a floor below [CapabilityRung.ONE] is refused at
     * construction (AMPR-459).
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
     * How this agent's Perceive phase renders its state into the prompt (AMPR-480). Handed to
     * [AgentReasoning] as its settings' `perceptionContextBuilder`, which *replaces*
     * `defaultPerceptionContext` rather than extending it (AMPR-403) — a caller that wants both
     * calls the default itself. Null, the default, keeps the default renderer, so every caller
     * that does not pass one is unchanged byte for byte. A hosted run's host seat is the one
     * production caller: it appends the "# World" section.
     */
    @Transient
    private val _perceptionContextBuilder: ((AgentState) -> String)? = null,
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
     * Sets the [PhaseSparkLibrary] consulted by the agent's [PhaseSparkManager].
     * Attaching a library is what turns declarative sparks on for this agent
     * alone: [createPhaseSparkManager] hands its manager the gate as a value,
     * `true` exactly when a library is set. Must be called before the agent
     * enters its first cognitive phase (the manager is constructed lazily on
     * first phase entry).
     */
    internal fun setPhaseSparkLibrary(library: PhaseSparkLibrary?) {
        _phaseSparkLibrary = library
    }

    override fun createPhaseSparkManager(): PhaseSparkManager<S> =
        PhaseSparkManager.createWithLibrary(
            agent = this,
            phaseConfig = agentConfiguration.cognitiveConfig.phaseSparks,
            library = _phaseSparkLibrary,
            declarativeSparks = _phaseSparkLibrary != null,
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

    init {
        // AMPR-459: refused before the stack is built, so an agent that would run on the
        // device fails where it is declared rather than on its first model call.
        requireOffTheDevice(agentId, _minimumRung, _aiConfiguration)

        // Initialize the SparkStack with the configured affinity
        reinitializeSparkStack()
    }

    // ========================================================================
    // Reasoning Infrastructure
    // ========================================================================

    private val reasoning: AgentReasoning by lazy {
        _reasoningOverride ?: AgentReasoning.create(
            config = agentConfiguration,
            actorId = id,
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
            perceptionContextBuilder = _perceptionContextBuilder
            _userGrantProvider?.let { provider ->
                execution { userGrants(provider) }
            }
        }
    }

    /**
     * Admits each plan step before its tool runs (AMPR-440, F16).
     *
     * Built over the same grant provider [reasoning]'s engine was handed, and over the
     * same deny-all `UserGrants()` the engine falls back to when this agent has none, so
     * the Breaker and the engine's own gate always answer a step the same way. It reads a
     * run's quotas through this agent's own door under [_runId] (AMPR-445), so an agent
     * built for no run can be handed no quota it could evaluate.
     *
     * `internal` because [dispatchPlanStep] admits through it from `propel/` (AMPR-442).
     */
    internal val breaker: Breaker by lazy {
        Breaker(
            userGrants = _userGrantProvider ?: { UserGrants() },
            eventApi = _eventApi,
            actorId = id,
            runId = _runId,
        )
    }

    /**
     * Whether this agent can actually run a tool (AMPR-405).
     *
     * False for an agent built with no [_executor]: its reasoning unit builds no
     * `ToolExecutionEngine`, so every plan step naming a tool refuses. A host that
     * drives the cognitive cycle should ask *before* spending a Perceive and a Plan
     * call on a cycle whose Execute phase cannot act — a misconfigured agent plans
     * plausibly and then refuses its own steps one at a time, which reads as several
     * tool failures rather than the single wiring mistake it is.
     *
     * The production factories ([AgentFactory], [SparkAgentFactory]) supply an
     * executor, so this is true for a factory-built agent unless the caller passed
     * `executor = null` on purpose.
     */
    val canExecuteTools: Boolean
        get() = reasoning.canExecuteTools

    /**
     * The `Executor.id` this agent's tool calls are performed by (AMPR-443, F17), or
     * [ExecutionContext.NO_EXECUTOR_ID] for an agent built with none — never [id]: the agent
     * decides a step, and the executor performs it.
     */
    private val boundExecutorId: ExecutorId
        get() = _executor?.id ?: ExecutionContext.NO_EXECUTOR_ID

    /**
     * This agent's reasoning unit — the phase services, reached as the suspending
     * functions they are (AMPR-393).
     *
     * The `runLLMTo*` lambdas below are the same services wrapped in
     * [runBlockingCompat] and [DEFAULT_LLM_CALL_TIMEOUT], which is what `AutonomousAgent`'s
     * loop needs and what a hosted run must not have: blocking a thread throws
     * outright on JS and wasm, and a run's caller owns its own time limits. A
     * suspend-only driver therefore needs the unit itself. (A host that drives the
     * public phase methods one at a time has the timed overloads instead — see
     * [perceiveState] — which suspend and take the caller's bound, AMPR-456.)
     *
     * `internal` because it is a seam, not a surface: a consumer drives phases
     * through [RunHost][link.socket.ampere.propel.RunHost], which is the entry
     * point that brackets them, stamps the run and closes the loop. Handing the
     * unit out publicly would make the un-bracketed, un-recorded call the easy one.
     */
    internal val reasoningUnit: AgentReasoning
        get() = reasoning

    /**
     * What a hosted run's RECALL asks this seat's memory for (AMPR-393, AMPR-394).
     *
     * Delegates to [memoryContextForTask] so a hosted run's RECALL asks the same
     * question the loop's does — one definition of what a task's `MemoryContext` is,
     * which is the reason that method is `protected` rather than private (AMPR-388).
     *
     * @param scopes The run's [recall scopes][link.socket.ampere.propel.HostedAgent.recallScopes],
     *   which become the context's tags. Empty leaves the query what the loop's is.
     * @param query The consumer's [RecallPolicy.query][link.socket.ampere.propel.RecallPolicy.query],
     *   which replaces the task's description. Null keeps it (AMPR-457).
     * @param taskType The consumer's [RecallPolicy.taskType][link.socket.ampere.propel.RecallPolicy.taskType],
     *   which replaces the task's bucket. Null keeps it (AMPR-457).
     */
    internal fun recallContextForRun(
        task: Task,
        scopes: Set<String>,
        query: String? = null,
        taskType: String? = null,
    ): MemoryContext {
        val context = memoryContextForTask(task, tags = scopes)
        return context.copy(
            taskType = taskType ?: context.taskType,
            description = query ?: context.description,
        )
    }

    /**
     * What Recall finds for [context], under this agent's own memory, publishing
     * nothing (AMPR-394).
     *
     * A hosted run publishes one `KnowledgeRecalled` of its own covering both this and
     * whatever the consumer's corpora returned, so this must not publish a second one
     * for the memory half alone. Empty with no memory service wired, and empty rather
     * than failing when the recall itself fails — a seat whose store is unreadable
     * plans without it, which is what the loop's own Recall does too.
     */
    internal suspend fun recallForRun(context: MemoryContext, limit: Int): List<KnowledgeWithScore> =
        memoryService?.scoreRecall(context, limit)?.getOrElse { emptyList() }.orEmpty()

    // ========================================================================
    // Neural Agent Implementation
    // ========================================================================

    override val runLLMToEvaluatePerception: (Perception<S>) -> Idea = { perception ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
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
                withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
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
            withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
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
            withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
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
     *
     * A skipped step is the one exception: it comes back as its own
     * [StepOutcome.Skipped], not folded (AMPR-446). The fold of a plan that only
     * skipped is a success, and handed to [AutonomousAgent.executePlan] that would
     * reach the next step as a done one.
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
        val stepOutcome = result.stepOutcomes.singleOrNull()
        // A skip is handed up as itself (AMPR-446). Folded, it is the success that did
        // nothing `PlanExecutor` makes of an all-skipped plan, and the walk would pass it on
        // to the next step as done; `AutonomousAgent` settles the verdict itself.
        if (stepOutcome is StepOutcome.Skipped) return stepOutcome
        return result.outcome.reportingStep(stepOutcome)
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
     *
     * The message carries the step's origin with it (AMPR-453), so the walk in
     * [AutonomousAgent.executePlan] — which only ever holds this outcome — labels the step's
     * text the way the step itself was labelled.
     */
    private fun Outcome.reportingStep(stepOutcome: StepOutcome?): Outcome {
        val report = stepOutcome?.detailText()?.takeIf { it.isNotBlank() } ?: return this
        return when (this) {
            is ExecutionOutcome.NoChanges.Success -> copy(message = report, origin = stepOutcome.origin)
            is ExecutionOutcome.NoChanges.Failure -> copy(message = report, origin = stepOutcome.origin)
            else -> this
        }
    }

    /**
     * Admits [step] and dispatches it on this agent: a one-line delegate to
     * [dispatchPlanStep], where the body lives (AMPR-442, H19).
     *
     * The step function is shared by both drivers, so it lives in `propel/` beside the
     * run that calls it directly. This member stays so the Arc path's callers —
     * [dispatchStepAsPlan] and [runSubPlanForTask] — and the tests written against it
     * reach the same function by the name they always have. See [dispatchPlanStep] for
     * strict tool-id routing, the Breaker's admission (AMPR-440), reasoning steps
     * (AMPR-407), held tools (AMPR-425) and [runRules], the run's own quota rules
     * (AMPR-445).
     */
    internal suspend fun executePlanStep(
        step: Task,
        parentTask: Task,
        priorResults: List<StepOutcome>,
        runRules: List<Pair<Rule, Hardness>> = emptyList(),
    ): StepResult = dispatchPlanStep(this, step, parentTask, priorResults, runRules)

    /**
     * Carries out a step that nominated no tool (AMPR-407).
     *
     * The step is one model call by this seat — the agent the Plan phase's step
     * was handed to — tagged [CognitivePhase.EXECUTE] like every other model
     * call Execute makes, and prompted with the step's description, the task it
     * belongs to, and what the steps before it produced. [priorResults] is the
     * same chain a tool step's parameter strategy renders (AMPR-408, AMPR-412):
     * a reasoning step reads a tool's result, and a tool step reads a reasoning
     * step's conclusion, through the one carrier. The text that comes back *is*
     * the step's result: it goes on the [StepResult] as its details (so
     * `PlanStepCompleted` carries it into the trace), and from there onto the
     * chain the steps after it are handed.
     *
     * Until AMPR-407 this returned `StepResult.success(details = "reasoning
     * step (no toolToUse)")` with no call at all, so a plan whose first step
     * was "decide the approach" had that step done by nobody and reported done.
     * Every way this can fail to produce text is therefore a step failure, not
     * a success — including having no LLM transport to call, which is the state
     * a bench or a test agent is in. A host that wants tool-less steps to stay
     * free says so with [ReasoningStepConfig.execute]`= false`, and then gets
     * the old behaviour by declaration rather than by accident.
     *
     * `internal` because [dispatchPlanStep] hands a tool-less step here (AMPR-442).
     * The call is this seat's own, made with its configuration and its prompt, so it
     * stays with the seat.
     */
    internal suspend fun executeReasoningStep(
        step: Task,
        description: String,
        parentTask: Task,
        priorResults: List<StepOutcome>,
    ): StepResult {
        val stepConfig: ReasoningStepConfig = agentConfiguration.cognitiveConfig.reasoningSteps
        if (!stepConfig.execute) {
            return StepResult.success(
                description = description,
                details = "reasoning step not executed: cognitiveConfig.reasoningSteps.execute is off",
            ).withOrigin(ContentOrigin.Own)
        }

        val text = try {
            reasoning.callLLM(
                prompt = buildReasoningStepPrompt(description, parentTask, priorResults),
                phase = CognitivePhase.EXECUTE,
            ).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StepResult.failure(
                description = description,
                error = "Reasoning step ${step.id} nominated no tool, so carrying it out is " +
                    "one model call — and that call failed: " +
                    "${e::class.simpleName}: ${e.message}. Set " +
                    "cognitiveConfig.reasoningSteps.execute = false to declare tool-less " +
                    "steps free instead of leaving them undone.",
                isCritical = true,
            )
        }

        if (text.isBlank()) {
            return StepResult.failure(
                description = description,
                error = "Reasoning step ${step.id} produced no text, so it reached no " +
                    "conclusion for the steps after it to read.",
                isCritical = true,
            )
        }

        // AMPR-453: the conclusion is the run's own only if everything it was reasoned
        // from was. A model shown an `External` result can repeat it, instructions
        // included, so the conclusion inherits that origin rather than laundering it.
        val origin = originOfAll(priorResults.map { it.origin })
        return StepResult.success(
            description = description,
            details = text,
        ).withOrigin(origin)
    }

    /**
     * The prompt one reasoning step is carried out with.
     *
     * Names what the step was asked to do and — when there is a wider task to
     * name — what the plan it belongs to is for. The live path hands Execute
     * one step at a time wrapped in its own one-step plan (AMPR-396), so the
     * step *is* its own parent there and a "Task:" line would repeat the step;
     * [runSubPlanForTask] is where the two differ. The earlier steps' results
     * come next, rendered by the same
     * [priorResultsSection][link.socket.ampere.agents.execution.priorResultsSection]
     * every default parameter strategy uses (AMPR-412), so a reasoning step and
     * a tool step are told the same things in the same words; and the
     * instruction to answer the step itself comes last, so the model ends on
     * the ask.
     */
    private fun buildReasoningStepPrompt(
        description: String,
        parentTask: Task,
        priorResults: List<StepOutcome>,
    ): String = buildString {
        appendLine("You are carrying out one step of a plan that is already underway.")
        appendLine(
            "This step nominates no tool, so it is yours to reason through: what you " +
                "write is the step's result, and the steps after it are given it as context.",
        )
        appendLine()
        val taskDescription = parentTask.promptDescription()
        if (taskDescription.isNotBlank() && taskDescription != description) {
            appendLine("Task: $taskDescription")
        }
        appendLine("Step to carry out: $description")
        appendLine()
        priorResultsSection(priorResults).takeIf { it.isNotBlank() }?.let { section ->
            appendLine(section)
        }
        appendLine(
            "Answer the step itself — the conclusion the later steps need, and nothing else.",
        )
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
     * from them. A reasoning step's conclusion travels the same way — as one of those
     * results — rather than being folded into `instructions`, so a tool step is told
     * it once, under the same heading as every other earlier result (AMPR-412).
     *
     * [step] is whichever of the two plan-step types the planner emitted, and
     * [description] is passed beside it because only those two carry one
     * (AMPR-410).
     *
     * `internal` because [dispatchPlanStep] builds each tool step's request through it
     * (AMPR-442). It reads the seat's pinned workspace and live file-access scope, which
     * are the agent's to know.
     */
    internal fun buildPlanStepRequest(
        step: Task,
        description: String,
        parentTask: Task,
        priorResults: List<StepOutcome>,
    ): ExecutionRequest<*> {
        val ticket = link.socket.ampere.agents.events.tickets.Ticket(
            id = "spark-task-${parentTask.id}",
            title = parentTask.id,
            description = description,
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
                // AMPR-443 (F17): this agent decides the step; the executor bound to it
                // performs it. Two values, and the agent's id is never the executor's.
                actorId = id,
                executorId = boundExecutorId,
                ticket = ticket,
                task = step,
                instructions = description,
            ),
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
            // AMPR-410: a step that already states its arguments is one of the two
            // writers of `ExecutionRequest.arguments` (AMPR-411 is the other, via
            // `SchemaParameterStrategy`). Copying them here rather than leaving them
            // on the step is what lets `ToolExecutionEngine` settle the call without a
            // parameter model call: it validates this one field against the tool's
            // schema and never looks at the task. Null when the step states none, which
            // is every `Task.CodeChange` and every step for a tool declaring no schema.
            arguments = (step as? Task.Step)?.arguments as? JsonObject,
        )
    }

    override val runLLMToExecuteTool: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome = { tool, request ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
                reasoning.executeTool(tool, request)
            }
        }
    }

    override val runLLMToEvaluateOutcomes: (List<Outcome>) -> Idea = { outcomes ->
        runBlockingCompat(ioDispatcher) {
            withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
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
     * the floor for one call without ever lowering the agent's own. A requirement
     * with no floor, or one below [CapabilityRung.ONE], arrives at `ONE`
     * ([AgentReasoning.callLLM], AMPR-459): a call this agent makes is never display
     * text. Null forwards no requirement.
     */
    fun callLLM(
        prompt: String,
        requirements: CapabilityRequirement?,
    ): String = runBlockingCompat(ioDispatcher) {
        withTimeout(DEFAULT_LLM_CALL_TIMEOUT) {
            reasoning.callLLM(prompt, requirements = requirements)
        }
    }

    // ========================================================================
    // Per-call bounds (AMPR-456)
    // ========================================================================

    /**
     * [perceiveState], with its model call bounded by [timeout] instead of
     * [DEFAULT_LLM_CALL_TIMEOUT] (AMPR-456).
     *
     * For a host that drives this agent one phase at a time and owns a budget per
     * request: a slow model's Perceive gets the time the host gave it, not a fixed 60 s.
     * The bound is a plain value on the call because it differs from one request to the
     * next. It covers the model call alone — the bookkeeping around it is
     * [perceiveWith]'s, the same as the unbounded method's.
     *
     * Unlike the unbounded method, this one suspends instead of blocking: the call runs
     * in the caller's coroutine, with no [runBlockingCompat] and no dispatcher switch of
     * its own, so [timeout] runs on the caller's clock (a test's virtual one under
     * `runTest`) and the call can run on JS and wasm. A call that overruns its bound
     * throws [TimeoutCancellationException][kotlinx.coroutines.TimeoutCancellationException]
     * at the bound, as the unbounded method does at 60 s.
     *
     * [timeout] is trailing and has no default, so it is always passed by name, and a
     * call without one is the unbounded method's: `perceiveState(state)` is unchanged.
     */
    suspend fun perceiveState(
        currentState: S,
        vararg newIdeas: Idea,
        timeout: Duration,
    ): Perception<S> = perceiveWith(currentState, newIdeas.toList()) { perception ->
        withTimeout(timeout) {
            reasoning.evaluatePerception(perception)
        }
    }

    /**
     * [determinePlanForTask], with its model call bounded by [timeout] instead of
     * [DEFAULT_LLM_CALL_TIMEOUT]. Suspends rather than blocks; see the timed [perceiveState].
     */
    suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore> = emptyList(),
        timeout: Duration,
    ): Plan = planWith {
        withTimeout(timeout) {
            reasoning.generatePlan(task, ideas.toList(), relevantKnowledge)
        }
    }

    /**
     * [runTool], with [timeout] bounding everything [AgentReasoning.executeTool] does — the
     * parameter call *and* the tool run, as the unbounded method's 60 s does. Suspends
     * rather than blocks; see the timed [perceiveState].
     */
    suspend fun runTool(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        timeout: Duration,
    ): ExecutionOutcome = runToolWith {
        withTimeout(timeout) {
            reasoning.executeTool(tool, request)
        }
    }

    /**
     * [evaluateNextIdeaFromOutcomes], with its model call bounded by [timeout] instead of
     * [DEFAULT_LLM_CALL_TIMEOUT]. Suspends rather than blocks; see the timed [perceiveState].
     */
    suspend fun evaluateNextIdeaFromOutcomes(
        vararg outcomes: Outcome,
        timeout: Duration,
    ): Idea = evaluateOutcomesWith {
        withTimeout(timeout) {
            reasoning.evaluateOutcomes(outcomes.toList(), memoryService).summaryIdea
        }
    }

    /**
     * [callLLM], with the call bounded by [timeout] instead of [DEFAULT_LLM_CALL_TIMEOUT].
     * [requirements] is raised to rung `ONE` and composes with the agent's floor exactly
     * as it does there. Suspends rather than blocks; see the timed [perceiveState].
     */
    suspend fun callLLM(
        prompt: String,
        requirements: CapabilityRequirement? = null,
        timeout: Duration,
    ): String = withTimeout(timeout) {
        reasoning.callLLM(prompt, requirements = requirements)
    }

    companion object {

        /**
         * How long one model call may take when its caller names no bound of its own
         * (AMPR-456): every `runLLMTo*` lambda, [callLLM], and [runSubPlanForTask].
         *
         * A host with its own per-request budget passes it to the timed overloads
         * ([perceiveState], [determinePlanForTask], [runTool],
         * [evaluateNextIdeaFromOutcomes], [callLLM] with `timeout =`) instead. The bound
         * here runs on the blocking path's real clock, inside [runBlockingCompat], so no
         * test scheduler can advance it.
         */
        val DEFAULT_LLM_CALL_TIMEOUT: Duration = 60.seconds

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
         * @param executor what the agent's tool calls dispatch through (AMPR-405).
         *   Null builds an agent that cannot run tools at all — its
         *   [AgentReasoning] builds no `ToolExecutionEngine` — so every plan step
         *   naming a tool fails. The production factories ([AgentFactory],
         *   [SparkAgentFactory]) supply one; pass a
         *   [FunctionExecutor][link.socket.ampere.agents.execution.executor.FunctionExecutor]
         *   when hand-building an agent that is expected to act.
         * @param outcomeRepository episodic store for the outcomes the agent's tool
         *   calls produce (AMPR-406). Only has anything to record when [executor] is
         *   set too — without one no engine is built and no tool dispatches.
         * @param aiConfiguration what the agent's calls resolve to when nothing routes
         *   them elsewhere; null uses the default. One that names the on-device provider
         *   or model is refused (AMPR-459), as by every factory here.
         * @param minimumRung the agent's floor (AMPR-219), `ONE` or above. Null declares
         *   none and keeps the agent on [aiConfiguration]; a floor below `ONE` is refused
         *   (AMPR-459).
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
            executor: Executor? = null,
            outcomeRepository: OutcomeMemoryRepository? = null,
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
                _executor = executor,
                _outcomeRepository = outcomeRepository,
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
         * surrounding `AgentFactory`. An `aiConfiguration` that names the
         * on-device provider or model is refused (AMPR-459).
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
            executor: Executor? = null,
            outcomeRepository: OutcomeMemoryRepository? = null,
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
                _executor = executor,
                _outcomeRepository = outcomeRepository,
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
         * strategy. An `aiConfiguration` that names the on-device provider
         * or model is refused (AMPR-459).
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
            executor: Executor? = null,
            outcomeRepository: OutcomeMemoryRepository? = null,
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
                _executor = executor,
                _outcomeRepository = outcomeRepository,
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
         * construction fails fast if it isn't present. An `aiConfiguration`
         * that names the on-device provider or model is refused (AMPR-459).
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
            executor: Executor? = null,
            outcomeRepository: OutcomeMemoryRepository? = null,
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
                _executor = executor,
                _outcomeRepository = outcomeRepository,
            )
            agent.spark<SparkBasedAgent<QualityState>>(roleSpark)
            return agent
        }
    }
}

/**
 * How a task names itself inside a prompt. Only [Task.CodeChange] carries a
 * description; anything else is named by its id, the same fallback
 * `PlanExecutor` uses when it has to describe a step it was handed.
 */
private fun Task.promptDescription(): String =
    // Through the shared accessor so a `Task.Step` parent is named by what it is
    // for rather than by its id (AMPR-410) — the reader this would otherwise have
    // been, silently.
    planStepDescription ?: id.ifBlank { "unnamed task" }

/**
 * Refuses the two declarations that put an agent's calls on the device (AMPR-459).
 *
 * A [SparkBasedAgent] runs the PROPEL loop, and rung ZERO serves display text only
 * (AMPR-372), so:
 *
 * - a floor below [CapabilityRung.ONE] is refused. `AgentLLMService` turns the floor
 *   into a requirement on every call, which makes every phase eligible for the device,
 *   PLAN included, and sends what the device cannot take cheapest-capable over the whole
 *   catalog rather than to the agent's own model. No floor is not refused: it keeps the
 *   configured model.
 * - a configuration that names the on-device provider or an on-device model is refused.
 *   It is what a call with no requirement resolves to, so every such call would run on
 *   the device with no floor involved. The model counts as well as the provider because
 *   the dispatching client keys on the model. The pairs are read through
 *   [AIConfiguration.getAvailableModels], never [AIConfiguration.provider], whose read
 *   decides which configuration of a set with backups answers.
 */
private fun requireOffTheDevice(
    agentId: AgentId,
    minimumRung: CapabilityRung?,
    configuration: AIConfiguration?,
) {
    require(minimumRung == null || minimumRung >= CapabilityRung.ONE) {
        "SparkBasedAgent '$agentId' declares minimumRung ${minimumRung?.ordinal}, below ONE. Rung ZERO " +
            "serves display text only, and this agent runs the PROPEL loop, so its floor is ONE or above " +
            "(AMPR-372). Leave it unset to keep the agent on its configured model. See the CognitiveRelay " +
            "concept, docs/concepts/cognitive-relay.md."
    }
    val onDevice = configuration?.getAvailableModels().orEmpty()
        .filter { (provider, model) -> provider.id == AIProvider_OnDevice.id || model is AIModel_OnDevice }
    require(onDevice.isEmpty()) {
        "SparkBasedAgent '$agentId' is configured with " +
            onDevice.joinToString { (provider, model) -> "${provider.id}/${model.name}" } +
            ", which executes on the device. Rung ZERO serves display text only, and this agent runs the " +
            "PROPEL loop (AMPR-372): configure a cloud model, and hand words for a person to read to " +
            "OnDeviceInferenceSession. See the CognitiveRelay concept, docs/concepts/cognitive-relay.md."
    }
}
