package link.socket.ampere.domain.arc

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
import kotlinx.datetime.Clock
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
 * A cancelled or failed run owes no `Knowledge` entry — it did not close its loop, and saying
 * otherwise would blunt the PropelLoop invariant. It owes a [CompletionManifest] instead
 * (AMPR-282): built once the run has settled, carried on [ArcOutcome.Cancelled] and
 * [ArcOutcome.Failed], and handed to [completionManifestSink] under [NonCancellable] so the record
 * survives the ending that caused it — including cancellation of the caller-owned scope, where no
 * outcome is returned.
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
@OptIn(ExperimentalAtomicApi::class)
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
    /**
     * The one clock the Arc tick reads (AMPR-335), handed to every phase and exposed to agents
     * as [SharedContext.clock]. Inject a fixed or test-driven clock to make a run's time
     * deterministic.
     */
    private val clock: Clock = Clock.System,
    /**
     * Optional destination for the [CompletionManifest] of every cancelled or failed run
     * (AMPR-282).
     *
     * Invoked under [NonCancellable], so it may suspend even though the run is being torn down.
     * Called before the caller's own cancellation is rethrown, so it sees runs that end without
     * returning an [ArcOutcome] at all. A sink that throws is swallowed: a failed record must not
     * change how the run is reported.
     */
    private val completionManifestSink: (suspend (CompletionManifest) -> Unit)? = null,
) {
    init {
        // Declared-but-unimplemented policies fail here, at construction, rather than on the
        // second trigger — a runtime that silently rejected under a `supersede` declaration
        // would be lying about its contract.
        require(arcConfig.concurrency.isImplemented) {
            "Arc '${arcConfig.name}' declares concurrency policy ${arcConfig.concurrency}, " +
                "which is not implemented; only ${ArcConcurrencyPolicy.REJECT} is supported"
        }
    }

    private var chargeResult: ChargeResult? = null
    private var flowResult: FlowResult? = null

    /**
     * Admission and liveness in one atomic cell (AMPR-358), so claiming the runtime is a single
     * compare-and-set rather than a read followed by a later write. See [RunState].
     */
    private val runState = AtomicReference(RunState.IDLE)

    /** The furthest phase this run has entered, for the [CompletionManifest] of an unfinished run. */
    @Volatile
    private var reachedPhase: ArcPhase? = null

    // `stop()` and `cancel()` are called from whatever thread owns the UI or the shutdown hook,
    // never from the Arc's own coroutine — so everything they touch, and everything they are
    // observed through, has to be volatile to be visible across that boundary.
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
     * @throws ArcRunRejectedException if a run is already in flight — see [admitRun]
     * @throws IllegalArgumentException if goal is blank
     */
    suspend fun execute(userGoal: String, runId: ArcRunId = generateUUID("arc-run")): ArcOutcome =
        execute(admitRun(), userGoal, runId)

    /**
     * Execute a run already admitted by [admitRun], spending [claim].
     *
     * Never re-checks admission — the claim *is* the admission. That is what lets
     * `ArcSession.start` refuse synchronously and then dispatch the run knowing it cannot be
     * refused a second time. The claim is released in the `finally` below however the run ends,
     * including a blank [userGoal].
     *
     * @throws IllegalStateException if [claim] was issued by another runtime or already spent
     */
    internal suspend fun execute(claim: RunClaim, userGoal: String, runId: ArcRunId): ArcOutcome {
        check(claim.runtime === this) { "Run claim belongs to a different runtime" }
        check(claim.spend()) { "Run claim has already been spent" }

        try {
            require(userGoal.isNotBlank()) { "User goal cannot be blank" }

            stopRequested = false
            cancelRequested = false
            chargeResult = null
            flowResult = null
            reachedPhase = null
            flowPhase = null

            // Published last, and read by callers as the signal that this run's state is reset —
            // so a `cancel()` that observes `isRunning()` cannot have its flag wiped by the lines
            // above. The claim is already held, so this is a plain store, not a second admission.
            runState.store(RunState.RUNNING)

            return runAdmitted(userGoal, runId)
        } finally {
            runState.store(RunState.IDLE)
        }
    }

    private suspend fun runAdmitted(userGoal: String, runId: ArcRunId): ArcOutcome {
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
            val attempt = runScope.async { runArc(userGoal, runId, runScope) }.await()
            return attempt.getOrElse { cause ->
                ArcOutcome.Failed(
                    runId = runId,
                    cause = cause,
                    manifest = closeOut(job, runId, TerminationReason.ERROR, cause),
                    chargeResult = chargeResult,
                    flowResult = partialFlow(),
                )
            }
        } catch (e: CancellationException) {
            val manifest = closeOut(job, runId, TerminationReason.CANCELLED, cause = null)

            // If the *caller* was cancelled this is not ours to swallow — rethrow it. The
            // manifest is already written by now; only the returned outcome is lost.
            coroutineContext.ensureActive()
            return ArcOutcome.Cancelled(
                runId = runId,
                manifest = manifest,
                chargeResult = chargeResult,
                flowResult = partialFlow(),
            )
        } finally {
            withContext(NonCancellable) {
                job.cancelAndJoin()
            }
            runJob = null
        }
    }

    /**
     * Run the three phases. A phase that throws is returned as a failed [Result] rather than
     * thrown, so [execute] gets the original throwable (not a stack-recovered copy from `await`)
     * and can close the run out after it has settled. Cancellation still propagates.
     */
    private suspend fun runArc(
        userGoal: String,
        runId: ArcRunId,
        runScope: CoroutineScope,
    ): Result<ArcOutcome.Completed> = try {
        // Phase 1: Charge - Initialize project context and spawn agents
        reachedPhase = ArcPhase.CHARGE
        val charge = executeCharge(userGoal, runId, runScope)
        chargeResult = charge

        // Phase 2: Flow - Execute agent loop
        reachedPhase = ArcPhase.FLOW
        val flow = executeFlow(charge)
        flowResult = flow

        // Phase 3: Pulse - Evaluate and capture learnings
        reachedPhase = ArcPhase.PULSE
        val pulse = executePulse(charge, flow)

        Result.success(
            ArcOutcome.Completed(
                runId = runId,
                chargeResult = charge,
                flowResult = flow,
                pulseResult = pulse,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Close out a run that ended without closing its loop: settle it, build its
     * [CompletionManifest], and hand that to [completionManifestSink].
     *
     * The run is settled first so the manifest sees every outcome the run recorded rather than a
     * snapshot racing the last of its coroutines. Both writes are [NonCancellable], so this
     * completes even when the caller itself is being cancelled.
     */
    private suspend fun closeOut(
        job: Job,
        runId: ArcRunId,
        endedBy: TerminationReason,
        cause: Throwable?,
    ): CompletionManifest {
        withContext(NonCancellable) { job.cancelAndJoin() }

        val manifest = CompletionManifest.fromIncompleteRun(
            runId = runId,
            endedBy = endedBy,
            cause = cause,
            reachedPhase = reachedPhase,
            chargeResult = chargeResult,
            flowResult = partialFlow(),
            flowCompleted = flowResult != null,
        )
        withContext(NonCancellable) { recordManifest(manifest) }
        return manifest
    }

    /** Flow's own result if it finished, otherwise a snapshot of how far it got. */
    private fun partialFlow(): FlowResult? = flowResult ?: flowPhase?.snapshot()

    /** Hand [manifest] to [completionManifestSink]. Must be called under [NonCancellable]. */
    private suspend fun recordManifest(manifest: CompletionManifest) {
        val sink = completionManifestSink ?: return
        try {
            sink(manifest)
        } catch (_: Throwable) {
            // Under NonCancellable a CancellationException here is the sink's own (a timeout it
            // set, say), not the run's — the run's is already being handled. Swallowing it too
            // keeps `execute` from throwing for a cancellation it has already turned into a
            // value. Best-effort by contract: the manifest is still carried on the outcome.
        }
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
        clock = clock,
    )

    private suspend fun executeFlow(chargeResult: ChargeResult): FlowResult {
        val phase = FlowPhase(
            arcConfig = arcConfig,
            agents = chargeResult.agents,
            goalTree = chargeResult.goalTree,
            maxTicks = maxFlowTicks,
            clock = clock,
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
            clock = clock,
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
     * Apply the Arc's declared [ArcConfig.concurrency] policy to a new run request (AMPR-284).
     *
     * The one place the answer to "a run is requested while one is in flight" lives: [execute]
     * and the Swift bridge's `ArcSession.start` both ask here rather than checking [isRunning]
     * themselves.
     *
     * **Atomic (AMPR-358).** Admission is a single compare-and-set that claims the runtime, not a
     * check followed by a later write. Of any number of concurrent requests, from any threads,
     * exactly one is admitted and every other one gets an [ArcRunRejectedException]. The runtime
     * stays claimed from the moment this returns until the admitted run's [execute] returns.
     *
     * The returned [RunClaim] must be handed to [execute], which releases it when the run ends,
     * or — if the run is abandoned before it is dispatched — to [releaseClaim]. A claim that
     * reaches neither leaves the runtime refusing every later run.
     *
     * @throws ArcRunRejectedException if a run is claimed or in flight under
     *   [ArcConcurrencyPolicy.REJECT]
     */
    internal fun admitRun(): RunClaim {
        when (val policy = arcConfig.concurrency) {
            ArcConcurrencyPolicy.REJECT ->
                if (!runState.compareAndSet(RunState.IDLE, RunState.CLAIMED)) {
                    throw ArcRunRejectedException(arcConfig.name, policy)
                }

            // Unreachable: the constructor refuses an unimplemented policy.
            ArcConcurrencyPolicy.SUPERSEDE,
            ArcConcurrencyPolicy.QUEUE,
            ArcConcurrencyPolicy.PARALLEL,
            -> error("Concurrency policy $policy is not implemented")
        }
        return RunClaim(this)
    }

    /**
     * Give back a [claim] that will never reach [execute]. A no-op for a claim [execute] has
     * already spent — that run releases the runtime itself.
     */
    internal fun releaseClaim(claim: RunClaim) {
        check(claim.runtime === this) { "Run claim belongs to a different runtime" }
        if (claim.spend()) {
            runState.compareAndSet(RunState.CLAIMED, RunState.IDLE)
        }
    }

    /**
     * Check if the runtime is currently executing.
     *
     * True only once a run's per-run state has been reset — not while a run is merely admitted —
     * so a caller that sees `true` can [cancel] and know the request will not be wiped.
     */
    fun isRunning(): Boolean = runState.load() == RunState.RUNNING

    /**
     * Proof that [admitRun] admitted one run on [runtime]. Spent exactly once: by [execute], or
     * by [releaseClaim] when the run is abandoned before dispatch.
     */
    internal class RunClaim internal constructor(internal val runtime: AmpereRuntime) {
        private val spent = AtomicBoolean(false)

        /** True for the first caller only. */
        internal fun spend(): Boolean = spent.compareAndSet(false, true)
    }

    /**
     * - [IDLE]: nothing admitted; [admitRun] may claim.
     * - [CLAIMED]: a run is admitted but has not reset its per-run state yet.
     * - [RUNNING]: the run's state is reset; [isRunning] is true and [cancel] is safe to issue.
     */
    private enum class RunState { IDLE, CLAIMED, RUNNING }

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
         * @param clock The clock the Arc tick reads
         * @return AmpereRuntime configured with the specified Arc
         */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            agentScope: CoroutineScope,
            maxFlowTicks: Int = 100,
            clock: Clock = Clock.System,
        ): AmpereRuntime {
            return AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDirPath.toPath(),
                agentScope = agentScope,
                maxFlowTicks = maxFlowTicks,
                clock = clock,
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
