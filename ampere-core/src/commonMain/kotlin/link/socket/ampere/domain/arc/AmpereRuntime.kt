package link.socket.ampere.domain.arc

import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.trace.ArcRunId
import link.socket.ampere.util.systemFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Runtime for executing Arc workflows through the Charge → Flow → Pulse lifecycle.
 *
 * The runtime orchestrates the three phases:
 * - **Charge**: Project analysis, goal decomposition, agent spawning
 * - **Flow**: Agent execution loop (perceive → remember → plan → execute)
 * - **Pulse**: Evaluation, learning capture, and delivery
 *
 * ### Lifetime and cancellation
 *
 * [agentScope] is caller-owned: the caller decides when spawned agents die. Each [execute] call
 * runs inside a per-run child scope of [agentScope], and that child is cancelled and joined
 * before [execute] returns — so no agent coroutine outlives the Arc run that spawned it, and
 * cancelling [agentScope] cancels any run in flight.
 *
 * There are two ways to end a run early:
 * - [stop] is cooperative and graceful. Flow finishes the tick it is on, terminates with
 *   [TerminationReason.MANUAL_STOP], and Pulse still runs — so the outcome is
 *   [ArcOutcome.Completed].
 * - [cancel] is a real coroutine cancellation. Flow stops at its next cancellation point,
 *   Pulse is skipped, and the outcome is [ArcOutcome.Cancelled].
 *
 * Example usage:
 * ```kotlin
 * val runtime = AmpereRuntime(
 *     arcConfig = ArcRegistry.get("startup-saas")!!,
 *     projectDir = Path("/path/to/project"),
 *     agentScope = myScope,
 * )
 * val outcome = runtime.execute("Implement user authentication")
 * ```
 */
class AmpereRuntime(
    private val arcConfig: ArcConfig,
    private val projectDir: Path,
    private val agentScope: CoroutineScope,
    private val fileSystem: FileSystem = systemFileSystem,
    private val maxFlowTicks: Int = 100,
    private val cognitiveRelay: CognitiveRelay? = null,
    private val executor: Executor? = null,
    private val upstreamLlmClient: UpstreamLlmClient? = null,
    /**
     * Optional factory for a per-agent [AgentEventApi] (AMPR-240). When
     * supplied, spawned agents publish `ProviderCallStartedEvent`/
     * `ProviderCallCompletedEvent` (and other telemetry) through it, which is
     * what lets [link.socket.ampere.trace.ArcTraceProjection] see this run's
     * model invocations. Null preserves the pre-existing behavior (no event
     * API, no persisted telemetry).
     */
    private val eventApiFactory: ((AgentId) -> AgentEventApi)? = null,
) {
    private var chargeResult: ChargeResult? = null
    private var flowResult: FlowResult? = null

    // `stop()` and `cancel()` are called from whatever thread owns the UI or the shutdown hook,
    // never from the Arc's own coroutine — so everything they touch, and everything they are
    // observed through, has to be volatile to be visible across that boundary.
    @Volatile
    private var isRunning = false

    @Volatile
    private var stopRequested = false

    /**
     * Sticky record of a [cancel] call, so one that lands in the window between a run being
     * marked running and its job existing is not lost. Checked once the job is in hand.
     */
    @Volatile
    private var cancelRequested = false

    /** Set for the duration of [execute] so [cancel] has something to cancel. */
    @Volatile
    private var runJob: CompletableJob? = null

    /** Set once Flow starts so [stop] can reach it and a cancelled run can still be summarised. */
    @Volatile
    internal var flowPhase: FlowPhase? = null
        private set

    /**
     * Execute the full Arc lifecycle for a given goal.
     *
     * Never throws for an Arc-level failure or cancellation — both are returned as
     * [ArcOutcome.Failed] and [ArcOutcome.Cancelled]. Cancellation of the *caller's* coroutine
     * is still propagated as a [CancellationException], as structured concurrency requires.
     *
     * @param userGoal The goal to accomplish
     * @param runId Ambient identity for this Arc execution (AMPR-240). Threaded down into every
     *   spawned agent so their `RoutingContext.workflowId` — and therefore
     *   `ProviderCallStartedEvent`/`ProviderCallCompletedEvent` — carries this run's id. Defaults to a
     *   freshly generated id so existing callers keep working unchanged. It is echoed back on
     *   every [ArcOutcome], including the cancelled and failed ones.
     * @return The terminal [ArcOutcome] of the run
     * @throws IllegalStateException if already running
     * @throws IllegalArgumentException if goal is blank
     */
    suspend fun execute(userGoal: String, runId: ArcRunId = generateUUID("arc-run")): ArcOutcome {
        require(!isRunning) { "Runtime is already executing" }
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }

        stopRequested = false
        cancelRequested = false
        chargeResult = null
        flowResult = null
        flowPhase = null

        // Published last, and read by callers as the signal that this run's state is reset —
        // so a `cancel()` that observes `isRunning` cannot have its flag wiped by the lines above.
        isRunning = true

        // A per-run child of the caller-owned scope: cancellable on its own (so `cancel()` does
        // not touch the caller), and cancelled + joined in the `finally` below so the run leaves
        // nothing alive behind it. SupervisorJob so one agent's failure cannot tear down the
        // caller's scope.
        val job = SupervisorJob(agentScope.coroutineContext[Job])
        val runScope = CoroutineScope(agentScope.coroutineContext + job)
        runJob = job

        // A `cancel()` that raced this setup had no job to act on. It left its flag behind.
        if (cancelRequested) {
            job.cancel(CancellationException(CANCELLATION_MESSAGE))
        }

        try {
            return runScope.async { runArc(userGoal, runId, runScope) }.await()
        } catch (e: CancellationException) {
            // If the *caller* was cancelled this is not ours to swallow — rethrow it.
            coroutineContext.ensureActive()
            return ArcOutcome.Cancelled(
                runId = runId,
                chargeResult = chargeResult,
                flowResult = flowResult ?: flowPhase?.snapshot(),
            )
        } finally {
            withContext(NonCancellable) {
                job.cancelAndJoin()
            }
            runJob = null
            isRunning = false
        }
    }

    private suspend fun runArc(
        userGoal: String,
        runId: ArcRunId,
        runScope: CoroutineScope,
    ): ArcOutcome = try {
        // Phase 1: Charge - Initialize project context and spawn agents
        val charge = executeCharge(userGoal, runId, runScope)
        chargeResult = charge

        // Phase 2: Flow - Execute agent loop
        val flow = executeFlow(charge)
        flowResult = flow

        // Phase 3: Pulse - Evaluate and capture learnings
        val pulse = executePulse(charge, flow)

        ArcOutcome.Completed(
            runId = runId,
            chargeResult = charge,
            flowResult = flow,
            pulseResult = pulse,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ArcOutcome.Failed(
            runId = runId,
            cause = e,
            chargeResult = chargeResult,
            flowResult = flowResult ?: flowPhase?.snapshot(),
        )
    }

    /**
     * Execute only the Charge phase.
     * Useful for testing or when you need to inspect the project context before proceeding.
     *
     * The agents in the returned [ChargeResult] are bound to [agentScope] rather than to a
     * per-run scope, because there is no run here to bound them to — the caller owns them.
     */
    suspend fun executeChargeOnly(userGoal: String, runId: ArcRunId = generateUUID("arc-run")): ChargeResult {
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }

        return newChargePhase(agentScope, runId).execute(userGoal)
    }

    private suspend fun executeCharge(
        userGoal: String,
        runId: ArcRunId,
        runScope: CoroutineScope,
    ): ChargeResult = newChargePhase(runScope, runId).execute(userGoal)

    private fun newChargePhase(agentScope: CoroutineScope, runId: ArcRunId): ChargePhase = ChargePhase(
        arcConfig = arcConfig,
        projectDir = projectDir,
        agentScope = agentScope,
        fileSystem = fileSystem,
        cognitiveRelay = cognitiveRelay,
        executor = executor,
        upstreamLlmClient = upstreamLlmClient,
        runId = runId,
        eventApiFactory = eventApiFactory,
    )

    private suspend fun executeFlow(chargeResult: ChargeResult): FlowResult {
        val phase = FlowPhase(
            arcConfig = arcConfig,
            agents = chargeResult.agents,
            goalTree = chargeResult.goalTree,
            maxTicks = maxFlowTicks,
        )
        flowPhase = phase

        // A `stop()` that landed during Charge takes effect at this phase boundary.
        if (stopRequested) {
            phase.stop()
        }

        return phase.execute()
    }

    private suspend fun executePulse(chargeResult: ChargeResult, flowResult: FlowResult): PulseResult {
        val pulsePhase = PulsePhase(
            arcConfig = arcConfig,
            flowResult = flowResult,
            projectContext = chargeResult.projectContext,
            goalTree = chargeResult.goalTree,
        )
        return pulsePhase.execute()
    }

    /**
     * Request a graceful stop of a running execution.
     *
     * Takes effect at the next safe point — between phases, or between Flow ticks. Flow
     * terminates with [TerminationReason.MANUAL_STOP] and Pulse still runs, so the run ends as
     * [ArcOutcome.Completed]. Use [cancel] to abandon the run instead.
     */
    fun stop() {
        stopRequested = true
        flowPhase?.stop()
    }

    /**
     * Cancel a running execution.
     *
     * Cancels the run's coroutine scope, so the Flow tick loop stops at its next cancellation
     * point and every agent coroutine spawned by the run is torn down. [execute] returns
     * [ArcOutcome.Cancelled] carrying whatever partial phase results exist.
     *
     * Sticky within a run: a call that arrives after [isRunning] goes true but before the run's
     * job exists is applied by [execute] as soon as it has one. A call made while no run is in
     * flight is discarded — the next [execute] starts clean.
     */
    fun cancel() {
        cancelRequested = true
        runJob?.cancel(CancellationException(CANCELLATION_MESSAGE))
    }

    /**
     * Check if the runtime is currently executing.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get the current Arc configuration.
     */
    fun getArcConfig(): ArcConfig = arcConfig

    companion object {
        internal const val CANCELLATION_MESSAGE = "Arc execution cancelled"

        /**
         * Create a runtime from an Arc configuration and a project directory path string.
         *
         * @param arcConfig The Arc configuration to use
         * @param projectDirPath The project directory as a string path
         * @param agentScope Caller-owned scope that spawned agents are bound to
         * @param maxFlowTicks Maximum ticks for the flow phase
         * @return AmpereRuntime configured with the specified Arc
         */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            agentScope: CoroutineScope,
            maxFlowTicks: Int = 100,
        ): AmpereRuntime {
            return AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDirPath.toPath(),
                agentScope = agentScope,
                maxFlowTicks = maxFlowTicks,
            )
        }

        /**
         * Create a runtime from team configuration for backward compatibility.
         *
         * This converts existing `ampere.yaml` team config to the Arc system:
         * ```yaml
         * team:
         *   - role: product-manager
         *   - role: engineer
         *   - role: qa-tester
         * ```
         *
         * Maps to `startup-saas` arc with the specified roles.
         *
         * @param teamRoles List of role names from team config
         * @param projectDir Project directory path
         * @param agentScope Caller-owned scope that spawned agents are bound to
         * @param fileSystem File system to use
         * @return AmpereRuntime configured with equivalent Arc config
         */
        fun fromTeamConfig(
            teamRoles: List<String>,
            projectDir: Path,
            agentScope: CoroutineScope,
            fileSystem: FileSystem = systemFileSystem,
        ): AmpereRuntime {
            val arcConfig = teamConfigToArcConfig(teamRoles)
            return AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDir,
                agentScope = agentScope,
                fileSystem = fileSystem,
            )
        }

        /**
         * Convert team configuration roles to an equivalent ArcConfig.
         *
         * Role name mappings:
         * - product-manager, pm → pm
         * - engineer, developer, dev → code
         * - qa-tester, qa, tester → qa
         * - architect → planner
         * - security-reviewer → scanner
         * - technical-writer → writer
         */
        fun teamConfigToArcConfig(teamRoles: List<String>): ArcConfig {
            val agents = teamRoles.map { role ->
                val normalizedRole = normalizeTeamRole(role)
                ArcAgentConfig(role = normalizedRole)
            }

            return ArcConfig(
                name = "team-config",
                description = "Arc generated from team configuration",
                agents = agents,
                orchestration = OrchestrationConfig(
                    type = OrchestrationType.SEQUENTIAL,
                    order = agents.map { it.role },
                ),
            )
        }

        private fun normalizeTeamRole(role: String): String {
            val lower = role.lowercase().replace("-", "").replace("_", "")
            return when {
                lower in setOf("productmanager", "pm", "product") -> "pm"
                lower in setOf("engineer", "developer", "dev", "coder") -> "code"
                lower in setOf("qatester", "qa", "tester", "quality") -> "qa"
                lower in setOf("architect", "planner") -> "planner"
                lower in setOf("securityreviewer", "security") -> "scanner"
                lower in setOf("technicalwriter", "writer", "docs") -> "writer"
                lower in setOf("analyst", "dataanalyst") -> "analyst"
                lower in setOf("monitor", "monitoring") -> "monitor"
                else -> role.lowercase().replace("-", "")
            }
        }
    }
}
