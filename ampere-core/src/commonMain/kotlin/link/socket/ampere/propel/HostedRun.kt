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
     *
     * Each step carries its arguments
     * ([Task.Step.arguments][link.socket.ampere.agents.domain.task.Task.Step.arguments])
     * and their hash ([argumentsHash], over canonical JSON), which is what the gate was
     * shown and what it approved (AMPR-454). A step that went back to the gate because its
     * arguments changed, and was approved, carries the arguments it ran with from then on.
     */
    val plan: Plan

    /**
     * Whether this run has put content from outside itself in front of a model
     * (AMPR-453, AMPR-385 row H24) — so a consumer's write gate can tighten once it has.
     *
     * Becomes true, and stays true, the moment `External` content is handed to a call that
     * renders it into a prompt. Two things raise it:
     *
     * - **RECALL that found anything.** Its "# Recalled context" section reaches every
     *   seat's prompt from then on, and everything recalled is `External` — a consumer's
     *   corpus is outside the run, and a stored learning can quote what an earlier run's
     *   tools read. So a run that recalled something has it up before its first PLAN.
     * - **An `External` step result handed on.** A step result whose
     *   [origin][StepOutcome.origin] is `External`, given to a later step's dispatch (its
     *   parameter prompt, or a reasoning step's own call), a later cycle's PLAN, or the
     *   decision call OBSERVE asks after a cycle.
     *
     * It is set *before* the call, so a tool reading `coroutineContext[HostedRun]` while its
     * step runs already sees it. It is read off the hand-over, not off the prompt bytes, so
     * it can be true when the call happened not to render the content — a step whose
     * arguments came inline from the plan makes no parameter call. That errs toward a
     * tighter gate, which is the safe direction. An `External` step that is the last thing
     * the run does, in a run that recalled nothing, leaves it false: no model was shown what
     * it read.
     *
     * The default answers true: an implementation that does not track this has to be
     * assumed to have read something.
     */
    val readExternalContent: Boolean
        get() = true

    /**
     * EXECUTE, plus a re-entry into OBSERVE → PLAN while cycles remain.
     *
     * Each step is dispatched to the seat the plan assigned it to: a step naming a
     * tool runs through that seat's tool engine, a step naming none is that seat's
     * own model call with the earlier steps' results in context. A step's
     * `PlanStepStarted`/`PlanStepCompleted` pair leaves through the seat that ran it.
     *
     * A step whose tool reports it is waiting on a person ends the run: the rest of
     * that cycle is skipped, no further cycle is planned, and the result is
     * [RunStatus.Held] (AMPR-425). On a host with a [HeldRunStore] the run is recorded
     * before this returns, and [RunResult.resumable] is true once it has been (AMPR-431).
     *
     * A step that fails does not: only the steps that depend on it
     * ([Task.Step.dependsOn][link.socket.ampere.agents.domain.task.Task.Step.dependsOn]),
     * directly or transitively, are skipped — each with the reason
     * `depends on failed step <id>` — and the independent steps still run. A run where
     * some steps succeeded and some failed is [RunStatus.Partial] (AMPR-450).
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
     * learned — only when the run [succeeded][RunStatus.Succeeded] — and ends the
     * run's task. Runs under `NonCancellable`, so it completes
     * from a `finally` after the scope was cancelled — which is the case it exists
     * for (AMPR-385 row H6; AMPR-282 is the Arc-path bug of the same shape).
     *
     * Idempotent. A run closed without [execute] — because the gate stopped it, or
     * because the caller changed its mind — records its outcome all the same.
     *
     * A [RunStatus.Held] run is the exception (AMPR-425): LEARN is entered and records
     * nothing, and the run's task is left blocked rather than ended, because the run is
     * waiting, not finished. Its held record is kept for [RunHost.resume]; a resumed run
     * that closes in any other status deletes it (AMPR-431).
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
     * How many PLAN calls the run made, counting from 1 — the one `open` charged
     * included.
     *
     * Less than [RunPolicy.cycles] when the run ended before the cap: a later cycle's
     * planner, shown the work done so far, answered with no steps (AMPR-424), or the
     * [PlanGate] trimmed a plan to nothing. That final PLAN *counts* — it was a model
     * call the run made, and the cycle it opened is the one that executed nothing — so
     * a two-cycle run whose second planner said "done" reports `2`, with the first
     * cycle's steps in [stepOutcomes]. A held run reports the cycle it held in.
     */
    val cyclesUsed: Int,
    /** Every step of every cycle, oldest first. */
    val stepOutcomes: List<StepOutcome> = emptyList(),
    /**
     * The run's own outcome, as LEARN records it.
     *
     * The last cycle's plan outcome for a run that executed, and a stated
     * success or failure for one that did not. For a [RunStatus.Held] run it is the
     * [ExecutionOutcome.Held][link.socket.ampere.agents.domain.outcome.ExecutionOutcome.Held]
     * of the step that held, with that step's reason and reference — and LEARN records
     * nothing.
     */
    val outcome: Outcome = Outcome.blank,
    /**
     * Whether the run put content from outside itself in front of a model before it
     * ended — [HostedRun.readExternalContent] as it stood when [HostedRun.execute]
     * returned (AMPR-453). Defaults to true for the same reason the run's own property
     * does: a result that does not say has to be assumed to have read something.
     */
    val readExternalContent: Boolean = true,
    /**
     * Whether [RunHost.resume] can carry this run on (AMPR-431): true only when it ended
     * [RunStatus.Held] and its record was committed to the host's [HeldRunStore]. False
     * for every other status, for a host with no store, and for a hold whose record could
     * not be written — that run is held all the same, and the failure is logged.
     */
    val resumable: Boolean = false,
)

/**
 * How a run ended.
 *
 * Five values, all from AMPR-385 row H20 as ruled on 2026-10-11: a step waiting on a
 * person is a full stop, which is [Held] (AMPR-425); a failed step skips only the steps
 * that depend on it, so a run can finish with some steps done and some not, which is
 * [Partial] (AMPR-450). A cancelled run is [Failed]; there is no separate cancelled
 * status.
 *
 * When more than one applies, the first of these wins: cancelled ([Failed]), stopped by
 * the gate ([Stopped]), any step held ([Held]), no step failed ([Succeeded]), some step
 * succeeded beside the failures ([Partial]), otherwise [Failed].
 *
 * LEARN reads this. The run's outcome is recorded for every status but [Held]; Knowledge
 * is stored only for [Succeeded].
 *
 * Adding a value breaks a consumer's exhaustive `when`, so a new one ships only in a
 * minor version, named in its release notes.
 */
@AmpereStableApi
enum class RunStatus {

    /** No step failed. Includes a run whose planner had nothing for it to do. */
    Succeeded,

    /**
     * At least one step failed and none succeeded — every step was a failure or was
     * skipped because one it depended on failed — or the run was cancelled. A run whose
     * plan was cut to nothing under [RunPolicy.maxStepsPerCycle] (every step named a tool
     * no seat has, AMPR-452) is failed too, unless an earlier cycle's step succeeded.
     */
    Failed,

    /**
     * The [PlanGate] ended the run.
     *
     * Not the same as "nothing ran": a multi-cycle run whose second plan was stopped
     * has the first cycle's steps in [RunResult.stepOutcomes] and that cycle's outcome
     * in [RunResult.outcome]. The same holds within a cycle when the gate stops a step
     * whose arguments changed after approval (AMPR-454): the steps before it ran, it and
     * every step after it are Skipped. What this says is which of the three ways a run can
     * end happened — the gate's, rather than the cap's or a failure's.
     */
    Stopped,

    /**
     * A step's tool is waiting on a person, so the run stopped there (AMPR-425).
     *
     * Every later step of that cycle is skipped and no further cycle is planned, whatever
     * [RunPolicy.cycles] allows: the steps after a hold would need an output it has not
     * produced. A hold outranks an earlier cycle's failure, because it is why the run
     * ended. Nothing is learned from a held run, and its task is left blocked rather than
     * ended — the run is waiting, not finished. With a [HeldRunStore] it is recorded, and
     * [RunHost.resume] carries it on where it stopped (AMPR-431).
     */
    Held,

    /**
     * Some steps succeeded and some failed, and the run was neither stopped, held nor
     * cancelled (AMPR-450).
     *
     * A failed step skips only the steps that depend on it, so the steps that did not
     * need it still ran; this is the run that did part of its work. Across cycles it is
     * the whole run that counts: a first cycle that failed and a second that succeeded
     * is partial, and so is a run with earlier successes whose later plan was cut to
     * nothing (AMPR-452). Its outcome is recorded and no Knowledge is stored — what was learned
     * is a claim about work that finished — and its task ends failed.
     */
    Partial,
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
