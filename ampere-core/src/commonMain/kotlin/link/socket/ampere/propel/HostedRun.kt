package link.socket.ampere.propel

import kotlin.coroutines.CoroutineContext
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.roster.RoleId

/**
 * One run over one roster, already charged (AMPR-385 row H1).
 *
 * [RunHost.open] returns one of these once the host seat has perceived, recalled,
 * observed, planned, and the plan has passed the [PlanGate]. So a `HostedRun` in hand
 * always has a [plan] to look at, and the two things left to do with it are [execute]
 * and [close].
 *
 * ```kotlin
 * val run = ampere.runs.open(roster, seats, goal, tools, RunPolicy(cycles = 2))
 * println(run.plan.tasks.map { it.planStepDescription })   // the gated plan
 * try {
 *     val result = run.execute()
 * } finally {
 *     run.close()                                          // LEARN, also on cancel
 * }
 * ```
 *
 * Suspend-only, and the caller owns the scope: nothing here blocks a thread, nothing
 * here imposes a timeout, and cancelling the scope that called [execute] cancels the
 * run. [close] is the exception — it completes under `NonCancellable`, because a run
 * that happened and recorded nothing is worse than a run that took a moment longer to
 * stop.
 *
 * It is a [CoroutineContext.Element] so a consumer can carry the run in the context
 * its own work runs under (`withContext(run) { … }`) and read it back from anywhere
 * beneath — `coroutineContext[HostedRun]?.runId` — rather than threading a run id
 * through every signature in between. The run does not put itself there; a run is a
 * value, and where it is visible is the caller's decision.
 *
 * Not thread-safe: one run, one driver. [execute] and [close] are each idempotent —
 * a second [execute] returns the first one's result, a second [close] does nothing —
 * so the `try`/`finally` above is safe even when the body already closed.
 */
@AmpereStableApi
interface HostedRun : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*>
        get() = Key

    /**
     * The run. Every event the run publishes carries it on its envelope (F4), which
     * is what makes `ArcTraceProjection.project(runId)` the whole of the run and not
     * a guess assembled from payloads.
     */
    val runId: RunId

    /**
     * The gated plan of the current cycle — an output, never an input.
     *
     * The host seat produced it and the [PlanGate] approved it. Before [execute] it
     * is what is about to run; after a cycle with `cycles > 1` it is the plan the
     * *latest* PLAN produced. [Plan.Blank] when the gate stopped the run.
     */
    val plan: Plan

    /**
     * EXECUTE, plus a re-entry into OBSERVE → PLAN while cycles remain.
     *
     * Each step is dispatched to the seat the plan assigned it to: a step naming a
     * tool runs through that seat's tool engine, a step naming none is that seat's
     * own model call with the earlier steps' results in context. A step's
     * `PlanStepStarted`/`PlanStepCompleted` pair leaves through the seat that ran it.
     *
     * Idempotent: calling it again returns the result of the first call rather than
     * running the plan twice.
     *
     * @throws kotlinx.coroutines.CancellationException if the calling scope is
     *   cancelled. The step in flight publishes its completed pair first, so the
     *   trace says where the run stopped. [close] still records the run.
     */
    suspend fun execute(): RunResult

    /**
     * LEARN, and the end of the run.
     *
     * Records one outcome for the run, stores what each seat that executed a step
     * learned, and ends the run's task. Runs under `NonCancellable`, so it completes
     * from a `finally` after the scope was cancelled — which is the case it exists
     * for (AMPR-385 row H6; AMPR-282 is the Arc-path bug of the same shape).
     *
     * Idempotent. A run closed without [execute] — because the gate stopped it, or
     * because the caller changed its mind — records its outcome all the same.
     */
    suspend fun close()

    /** The context key [HostedRun]s are read back under. */
    companion object Key : CoroutineContext.Key<HostedRun>
}

/** What one [HostedRun.execute] did. */
@AmpereStableApi
data class RunResult(
    /** The run this is the result of. */
    val runId: RunId,
    /** How the run ended. */
    val status: RunStatus,
    /**
     * How many cycles ran, counting from 1 — the PLAN `open` charged included.
     *
     * Less than [RunPolicy.cycles] when the planner ran out of steps before the cap
     * did, which is the normal way a multi-cycle run ends.
     */
    val cyclesUsed: Int,
    /** Every step of every cycle, oldest first. */
    val stepOutcomes: List<StepOutcome> = emptyList(),
    /**
     * The run's own outcome, as LEARN records it.
     *
     * The last cycle's plan outcome for a run that executed, and a stated
     * success or failure for one that did not.
     */
    val outcome: Outcome = Outcome.blank,
)

/**
 * How a run ended.
 *
 * Three values, not the four of AMPR-385 row H20 (`Succeeded`, `Partial`, `Failed`,
 * `Cancelled`): that row has no verdict, so neither the partial status nor the
 * dependent-skipping that would produce it is implemented here. A run with some steps
 * failed is [Failed], as `PlanExecutor` already reports it.
 */
@AmpereStableApi
enum class RunStatus {

    /** Every step that ran succeeded. */
    Succeeded,

    /** At least one step failed, or the run could not be driven at all. */
    Failed,

    /**
     * The [PlanGate] ended the run.
     *
     * Not the same as "nothing ran": a multi-cycle run whose second plan was stopped
     * has the first cycle's steps in [RunResult.stepOutcomes] and that cycle's outcome
     * in [RunResult.outcome]. What this says is which of the three ways a run can end
     * happened — the gate's, rather than the cap's or a failure's.
     */
    Stopped,
}

/** The seats of a run a [RunHost] has open (AMPR-385 row H9). */
@AmpereStableApi
data class OpenSeat(
    /** The run this seat is filled for. */
    val runId: RunId,
    /** The role being filled. */
    val role: RoleId,
    /** The agent filling it, which is also its event door's identity. */
    val agentId: AgentId,
    /** Whether this is the seat that plans. */
    val isHost: Boolean,
    /** What the run is for, as the goal describes itself. */
    val goal: String,
    /** The spark stack this seat was differentiated by, in application order. */
    val sparkNames: List<String> = emptyList(),
)
