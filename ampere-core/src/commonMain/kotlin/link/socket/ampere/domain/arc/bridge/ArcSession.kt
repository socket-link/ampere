package link.socket.ampere.domain.arc.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.relay.DEFAULT_EMISSION_BUFFER_CAPACITY
import link.socket.ampere.agents.events.relay.emissions
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcConcurrencyPolicy
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcOutcome
import link.socket.ampere.domain.arc.ArcRunRejectedException
import link.socket.ampere.domain.arc.CompletionManifest
import link.socket.ampere.domain.arc.CompletionManifestSink
import link.socket.ampere.domain.arc.TerminationReason
import link.socket.ampere.trace.ArcRunId
import link.socket.ampere.trace.ArcRunTrace
import link.socket.ampere.trace.ArcTraceProjection
import okio.Path.Companion.toPath

/**
 * How many Emissions a run holds for observers that attach after it started.
 *
 * Sized to cover the gap between a Swift `session.start(...)` and the `Task { for await ... }`
 * on the next line — a scheduling hop, not a real delay — with room for the opening burst of a
 * Charge phase.
 */
const val DEFAULT_EMISSION_REPLAY: Int = 32

/**
 * What [ArcSession.tryStart] hands back: a run, or the reason there is none (AMPR-357).
 *
 * A value rather than a thrown [ArcRunRejectedException] for the same reason [ArcOutcome] is
 * one: Kotlin/Native only turns a Kotlin exception into a Swift `Error` when the function
 * declares it with `@Throws`, and anything undeclared that crosses the boundary terminates the
 * process. Swift sees the two cases as `ArcStartResult.Started` and `ArcStartResult.Rejected`.
 */
sealed class ArcStartResult {
    /** The run was dispatched; [handle] observes it. */
    class Started(val handle: ArcRunHandle) : ArcStartResult()

    /**
     * The Arc's declared concurrency policy refused the run. The run already in flight is
     * untouched.
     *
     * @property arcName The Arc whose runtime refused the run.
     * @property policy The declared policy that produced the refusal.
     */
    class Rejected(
        val arcName: String,
        val policy: ArcConcurrencyPolicy,
    ) : ArcStartResult()
}

/**
 * Starts Arcs and hands back a handle to each one.
 *
 * The session owns the scope; the handle observes. That split is what keeps the bridge honest:
 * nothing here re-implements the Arc lifecycle, it only makes [AmpereRuntime] reachable from a
 * caller that cannot hold a `CoroutineScope` — which is every Swift call site, and the reason
 * App Intents are blocked without it.
 *
 * A goal that arrives while a run is in flight is handled by the Arc's declared
 * [ArcConfig.concurrency] policy — the same check [AmpereRuntime.execute] applies. Under the
 * default, [ArcConcurrencyPolicy.REJECT][link.socket.ampere.domain.arc.ArcConcurrencyPolicy.REJECT],
 * [start] refuses it with an
 * [ArcRunRejectedException][link.socket.ampere.domain.arc.ArcRunRejectedException].
 *
 * ### Swift
 *
 * Swift builds a session through [Companion.create], never this constructor — see the note
 * there on why a `CoroutineScope` cannot cross the boundary.
 *
 * ```swift
 * let session = ArcSession.companion.create(
 *     arcConfig: ArcRegistry.shared.getDefault(),
 *     projectDirPath: projectPath,
 *     maxFlowTicks: 100
 * )
 * defer { session.close() }
 * let handle = session.start(userGoal: "Add a health check endpoint")
 * ```
 *
 * [start] cannot be caught from Swift: its refusal is an undeclared Kotlin exception there, and
 * it terminates the process. A caller that may fire while a run is in flight — an App Intent,
 * say — uses [tryStart] and switches on the value:
 *
 * ```swift
 * switch try session.tryStart(userGoal: goal) {
 * case let started as ArcStartResult.Started:
 *     observe(started.handle)
 * case let rejected as ArcStartResult.Rejected:
 *     // rejected.policy == .reject: a run is already in flight; it carries on untouched.
 *     report("\(rejected.arcName) is busy")
 * default:
 *     break
 * }
 * ```
 *
 * The `try` is for a blank goal only, which [tryStart] declares with `@Throws` — bad input is a
 * Swift `Error`, a busy runtime is a value.
 *
 * @param scope Caller-owned. Its lifetime bounds every run this session starts.
 * @param runtime The Arc runtime to drive.
 * @param eventSerialBus The bus the run's Emissions are published on.
 * @param traceProjection Optional; supplying it is what makes [ArcRunHandle.trace] return a
 *   folded ledger instead of null.
 * @param emissionReplay Emissions held for late observers. See [DEFAULT_EMISSION_REPLAY].
 * @param emissionCapacity Emissions buffered for a slow observer before the oldest is dropped.
 * @param onEmissionsDropped Called with the running number of Emissions lost to a slow
 *   observer. Loss is reported, never silent — a progress surface that has skipped updates
 *   should be able to say so.
 */
class ArcSession(
    private val scope: CoroutineScope,
    private val runtime: AmpereRuntime,
    private val eventSerialBus: EventSerialBus,
    private val traceProjection: ArcTraceProjection? = null,
    private val emissionReplay: Int = DEFAULT_EMISSION_REPLAY,
    private val emissionCapacity: Int = DEFAULT_EMISSION_BUFFER_CAPACITY,
    private val onEmissionsDropped: (droppedTotal: Long) -> Unit = {},
) {
    /**
     * Secondary constructor for the Objective-C export, which does not carry Kotlin default
     * arguments across the boundary. The three parameters a Kotlin caller always has.
     */
    constructor(
        scope: CoroutineScope,
        runtime: AmpereRuntime,
        eventSerialBus: EventSerialBus,
    ) : this(
        scope = scope,
        runtime = runtime,
        eventSerialBus = eventSerialBus,
        traceProjection = null,
    )

    /** Set only by [Companion.create]; the scope this session must clean up after itself. */
    private var ownedScope: CoroutineScope? = null

    /**
     * The bus this session's Emissions travel on.
     *
     * The only way to reach it for a session built by [Companion.create], which makes its own —
     * a host that wants to watch anything beyond Emissions needs this handle.
     */
    val bus: EventSerialBus
        get() = eventSerialBus

    /** Start an Arc for [userGoal] under a freshly generated run identity. */
    fun start(userGoal: String): ArcRunHandle = start(userGoal, generateUUID("arc-run"))

    /**
     * Start an Arc for [userGoal] under [runId].
     *
     * Returns as soon as the run is dispatched — the Arc itself runs on the session's scope.
     * The Emission subscription is registered *before* this returns, so an observer attached on
     * the returned handle cannot miss the run's opening.
     *
     * Admission is decided here, synchronously, before a handle exists (AMPR-358): of any number
     * of concurrent calls, exactly one returns a handle and the rest throw. A handle is never
     * returned for a run that is then refused.
     *
     * @throws IllegalArgumentException if [userGoal] is blank
     * @throws link.socket.ampere.domain.arc.ArcRunRejectedException if the Arc's concurrency
     *   policy refuses a run while [runtime] is already executing
     */
    fun start(userGoal: String, runId: ArcRunId): ArcRunHandle {
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }
        return dispatch(runtime.admitRun(), userGoal, runId)
    }

    /**
     * [start], with a refusal returned as [ArcStartResult.Rejected] instead of thrown — the form
     * Swift can handle (AMPR-357). Starts under a freshly generated run identity.
     *
     * @throws IllegalArgumentException if [userGoal] is blank
     */
    @Throws(IllegalArgumentException::class)
    fun tryStart(userGoal: String): ArcStartResult = tryStart(userGoal, generateUUID("arc-run"))

    /**
     * [start] under [runId], with a refusal returned as [ArcStartResult.Rejected] instead of
     * thrown.
     *
     * @throws IllegalArgumentException if [userGoal] is blank
     */
    @Throws(IllegalArgumentException::class)
    fun tryStart(userGoal: String, runId: ArcRunId): ArcStartResult {
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }
        val claim = try {
            runtime.admitRun()
        } catch (e: ArcRunRejectedException) {
            return ArcStartResult.Rejected(arcName = e.arcName, policy = e.policy)
        }
        return ArcStartResult.Started(dispatch(claim, userGoal, runId))
    }

    /** Dispatch the run [claim] admitted. Callers have already validated [userGoal]. */
    private fun dispatch(claim: AmpereRuntime.RunClaim, userGoal: String, runId: ArcRunId): ArcRunHandle {
        val emissions: MutableSharedFlow<Emission>
        val pump: Job
        try {
            emissions = MutableSharedFlow(replay = emissionReplay)

            // UNDISPATCHED so the bus subscription is registered on the calling thread, before
            // this function returns. Dispatching it would open a window in which the first
            // tick's Emissions are published to nobody.
            pump = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                eventSerialBus
                    .emissions(
                        runId = runId,
                        capacity = emissionCapacity,
                        onDropped = onEmissionsDropped,
                    )
                    .collect { emissions.emit(it) }
            }
        } catch (e: Throwable) {
            runtime.releaseClaim(claim)
            throw e
        }

        // UNDISPATCHED so `execute` takes ownership of the claim before this function returns.
        // An UNDISPATCHED coroutine starts even if [scope] is already cancelled, which is what
        // guarantees the claim reaches `execute`'s `finally` — a dispatched start could be
        // cancelled before it ran and leave the runtime claimed forever.
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                runtime.execute(claim, userGoal, runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // `execute` maps Arc-level failures itself; this catches the ones it cannot,
                // so an unexpected throw cannot tear down the caller's session scope. Such a throw
                // comes from outside the run's phases, so the manifest can only honestly say that
                // nothing is known to have started.
                ArcOutcome.Failed(
                    runId = runId,
                    cause = e,
                    manifest = CompletionManifest.fromIncompleteRun(
                        runId = runId,
                        endedBy = TerminationReason.ERROR,
                        cause = e,
                        reachedPhase = null,
                        chargeResult = null,
                        flowResult = null,
                        flowCompleted = false,
                    ),
                )
            }
        }

        // Release the bus subscription the moment the run is terminal. Without this the handler
        // outlives the run and the bus grows one dead subscriber per Arc.
        scope.launch {
            outcome.join()
            pump.cancel()
        }

        return ArcRunHandle(
            runId = runId,
            runtime = runtime,
            scope = scope,
            outcome = outcome,
            emissions = emissions,
            traceProjection = traceProjection,
        )
    }

    /**
     * Release the scope this session made for itself in [Companion.create], cancelling any run
     * still in flight.
     *
     * A no-op for a session built on a caller-supplied scope — that lifetime is not ours to end.
     */
    fun close() {
        ownedScope?.cancel()
        ownedScope = null
    }

    companion object {
        /**
         * Build a session that owns its scope and its bus.
         *
         * This exists because of one hard fact about the export: `kotlinx.coroutines` is a plain
         * dependency of `ampere-core`, not an `export()`ed one, so none of its constructors
         * cross the Objective-C boundary. `CoroutineScope` reaches Swift only as an opaque
         * protocol with no way to make one. Every parameter here is something Swift *can*
         * build — an [ArcConfig], a path string, an Int — and the coroutine machinery stays on
         * the Kotlin side of the line.
         *
         * The caller owns the returned session and must [close] it.
         *
         * @param arcConfig Which Arc to run. `ArcRegistry.getDefault()` is the usual answer.
         * @param projectDirPath Directory the Arc reads its project context from.
         * @param maxFlowTicks Tick ceiling for the Flow phase.
         */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            maxFlowTicks: Int,
        ): ArcSession = create(
            arcConfig = arcConfig,
            projectDirPath = projectDirPath,
            maxFlowTicks = maxFlowTicks,
            clock = Clock.System,
        )

        /**
         * [create], with the clock the constructed runtime's Arc tick reads (AMPR-335).
         *
         * A separate overload rather than a defaulted parameter: the Objective-C export drops
         * Kotlin defaults, and the three-argument form is the one Swift already calls.
         *
         * @param clock Passed to the [AmpereRuntime] this session constructs.
         */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            maxFlowTicks: Int,
            clock: Clock,
        ): ArcSession = build(arcConfig, projectDirPath, maxFlowTicks, clock, database = null)

        /**
         * [create], keeping what the session's runs leave behind in [database] (AMPR-359).
         *
         * A run that is cancelled or fails writes its [CompletionManifest] there as it settles —
         * including one whose session is closed under it, which returns no outcome at all — and
         * [ArcRunHandle.trace] reads it back as [ArcRunTrace.completion]. Without a database the
         * manifest lives only on the returned outcome.
         *
         * [ArcSession.close] cancels a run still in flight, and that run writes its manifest as it
         * unwinds, so keep [database]'s driver open until the run has settled: `await` or `cancel`
         * its handle first when the record matters.
         *
         * From Swift, open the database with `createIosDriver`:
         * ```swift
         * let driver = IOSDatabaseDriverKt.createIosDriver(dbName: "ampere.db")
         * let session = ArcSession.companion.create(
         *     arcConfig: ArcRegistry.shared.getDefault(),
         *     projectDirPath: projectPath,
         *     maxFlowTicks: 100,
         *     database: DatabaseCompanion.shared.invoke(driver: driver)
         * )
         * ```
         *
         * @param database Where manifests are written and traces are read from. Its schema must
         *   already be current, as every platform's driver factory leaves it.
         */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            maxFlowTicks: Int,
            database: Database,
        ): ArcSession = build(arcConfig, projectDirPath, maxFlowTicks, Clock.System, database)

        /** [create] with both a [clock] and a [database]; see the overloads that take one each. */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            maxFlowTicks: Int,
            clock: Clock,
            database: Database,
        ): ArcSession = build(arcConfig, projectDirPath, maxFlowTicks, clock, database)

        private fun build(
            arcConfig: ArcConfig,
            projectDirPath: String,
            maxFlowTicks: Int,
            clock: Clock,
            database: Database?,
        ): ArcSession {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventSerialBus(scope = scope)

            // The event api subscribes to the bus as it is built. Safe on a caller's thread here —
            // Swift's main one included — because nothing else holds this bus yet, so the lock it
            // takes is never contended.
            val manifestSink = database?.let {
                CompletionManifestSink(
                    eventApi = AgentEventApi(
                        agentId = CompletionManifestSink.DEFAULT_AGENT_ID,
                        eventRepository = EventRepository(DEFAULT_JSON, scope, it),
                        eventSerialBus = bus,
                        clock = clock,
                    ),
                )
            }

            val session = ArcSession(
                scope = scope,
                runtime = AmpereRuntime(
                    arcConfig = arcConfig,
                    projectDir = projectDirPath.toPath(),
                    agentScope = scope,
                    maxFlowTicks = maxFlowTicks,
                    clock = clock,
                    completionManifestSink = manifestSink?.let { it::record },
                ),
                eventSerialBus = bus,
                traceProjection = database?.let { ArcTraceProjection(it) },
            )
            session.ownedScope = scope

            return session
        }
    }
}
