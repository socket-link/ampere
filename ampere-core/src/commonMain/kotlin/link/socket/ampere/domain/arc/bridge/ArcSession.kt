package link.socket.ampere.domain.arc.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.relay.DEFAULT_EMISSION_BUFFER_CAPACITY
import link.socket.ampere.agents.events.relay.emissions
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcOutcome
import link.socket.ampere.trace.ArcRunId
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
 * Starts Arcs and hands back a handle to each one.
 *
 * The session owns the scope; the handle observes. That split is what keeps the bridge honest:
 * nothing here re-implements the Arc lifecycle, it only makes [AmpereRuntime] reachable from a
 * caller that cannot hold a `CoroutineScope` — which is every Swift call site, and the reason
 * App Intents are blocked without it.
 *
 * One run at a time per [runtime]: [start] rejects a goal while a run is in flight, matching
 * [AmpereRuntime.execute]'s own contract.
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
     * @throws IllegalArgumentException if [userGoal] is blank
     * @throws IllegalStateException if [runtime] is already executing
     */
    fun start(userGoal: String, runId: ArcRunId): ArcRunHandle {
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }
        check(!runtime.isRunning()) { "Runtime is already executing" }

        val emissions = MutableSharedFlow<Emission>(replay = emissionReplay)

        // UNDISPATCHED so the bus subscription is registered on the calling thread, before this
        // function returns. Dispatching it would open a window in which the first tick's
        // Emissions are published to nobody.
        val pump = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventSerialBus
                .emissions(
                    runId = runId,
                    capacity = emissionCapacity,
                    onDropped = onEmissionsDropped,
                )
                .collect { emissions.emit(it) }
        }

        val outcome = scope.async {
            try {
                runtime.execute(userGoal, runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // `execute` maps Arc-level failures itself; this catches the ones it cannot,
                // so an unexpected throw cannot tear down the caller's session scope.
                ArcOutcome.Failed(runId = runId, cause = e)
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
        ): ArcSession {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val session = ArcSession(
                scope = scope,
                runtime = AmpereRuntime(
                    arcConfig = arcConfig,
                    projectDir = projectDirPath.toPath(),
                    agentScope = scope,
                    maxFlowTicks = maxFlowTicks,
                ),
                eventSerialBus = EventSerialBus(scope = scope),
            )
            session.ownedScope = scope

            return session
        }
    }
}
