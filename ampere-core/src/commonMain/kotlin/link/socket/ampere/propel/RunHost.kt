package link.socket.ampere.propel

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster

/**
 * Opens runs over a roster (AMPR-385 row H1, amended 2026-10-07).
 *
 * The consumer-facing entry into the PROPEL loop. Before this there was none: the
 * phase services were public and worked, `AutonomousAgent.runtimeLoop` was
 * `protected` and infinite, and `AmpereRuntime` needed a project directory and built
 * code agents — so a consumer wanting one run per goal assembled the loop itself out
 * of Ampere's parts. This is that loop, packaged, over a roster of seats.
 *
 * What the consumer supplies: the roster, who fills each seat, the goal, the tools,
 * and a policy. What Ampere does: the host seat perceives, recalls, observes and
 * plans; the consumer may hold the plan at a [PlanGate]; each step runs on the seat
 * the plan assigned it to; closing the run learns.
 *
 * Obtain one from [AmpereInstance.runs][link.socket.ampere.api.AmpereInstance.runs],
 * or build one with [rosterRunHost] when composing by hand.
 *
 * It is the public entry into the same phase services `FlowPhase` calls (AMPR-328 row
 * F18), beside that tick rather than inside it: `FlowPhase` keeps owning the
 * multi-agent tick, and there is no second planner. Row H19 — lifting the tick body
 * and the judgment seams into shared functions both callers use — has no verdict, so
 * `FlowPhase` is untouched here.
 */
@AmpereStableApi
interface RunHost {

    /**
     * Charge a run for [goal] and return it with its first plan already gated.
     *
     * Runs PERCEIVE → RECALL → OBSERVE → PLAN on the roster's host seat, publishes
     * the run's `TaskCreated`/`TaskStarted`, and awaits
     * [RunPolicy.planGate]. EXECUTE and LEARN are [HostedRun.execute] and
     * [HostedRun.close], separately, so a consumer can read
     * [HostedRun.plan] in between — which is the whole point of a gate it can also
     * inspect afterwards.
     *
     * Suspending for as long as those four phases take, which includes however long
     * the gate holds. The caller owns the scope; nothing here blocks a thread and
     * nothing here imposes a timeout.
     *
     * @param roster The seats and which of them hosts. Only [Roster.host],
     *   [Roster.all] and [Roster.verifier] are read.
     * @param seats Who fills each seat. Must contain [Roster.host]; a role with no
     *   seat is a role this run does not use, and a seat naming a role the roster
     *   does not hold is a mistake worth failing on.
     * @param goal What the run is for. The host seat's PERCEIVE and PLAN prompts are
     *   about it, RECALL is scoped to it, and its description is what the run's task
     *   events carry.
     * @param tools Every tool this run may dispatch. A seat may run those of them its
     *   [RoleConfig.tools][link.socket.ampere.roster.RoleConfig.tools] declares, and
     *   no others: the roster states what each seat needs, this says what is wired.
     *   Each one reaches its seat under the seat-namespaced id `<seat>/<tool>`
     *   (AMPR-413), which is the id a plan step names and the id the run dispatches
     *   on, so two seats sharing a tool stay unambiguous about who runs it.
     * @param policy Cycles, LEARN and the gate. See [RunPolicy].
     * @param runId The id this run is known by (AMPR-385 row H27), for a caller that
     *   reserved budget, opened a trace or filed records under an id before the run
     *   existed. It becomes [HostedRun.runId] and [RunResult.runId], the run id on the
     *   envelope of every event the run and its seats publish, the id of the run's task
     *   lifecycle, and the `ticketId` and `runId` of the outcome LEARN records. Null —
     *   the default — mints one. Uniqueness is checked against the runs *this host*
     *   has open and nothing else: an id a closed run used is accepted again, and no
     *   claim is made about ids in use by another host or another process.
     * @param workspace The checkout this run works in (AMPR-496), or null for a run that
     *   touches no files. One per run, never per seat: every seat of the run stamps it on
     *   every step request it builds, so `write_code_file`, `read_code_file` and a harness
     *   behind `harness_code_change` all find it on the request and work in the same tree.
     *   A held run records it, and [resume] carries it on. Null — the default — stays null:
     *   there is no default workspace, and a code tool asked to act without one refuses
     *   rather than writing into the process's working directory (AMPR-300).
     * @throws IllegalArgumentException if no seat fills the roster's host, if a seat
     *   names a role the roster does not hold, if two seats share an agent id, if a
     *   seat is asked a question it has no decision transport to answer on —
     *   [RunPolicy.goalCheck] is [GoalCheck.Record] with no
     *   [HostedAgent.upstreamDecisionClient] on the host seat, or a verifier seat
     *   declaring [HostedAgent.questions] with none on its own (AMPR-421) — if [runId]
     *   is blank, or if [runId] names a run this host still has open. Every refusal
     *   happens before any seat is built or any phase is charged.
     */
    suspend fun open(
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        goal: Task,
        tools: Set<Tool<*>> = emptySet(),
        policy: RunPolicy = RunPolicy(),
        runId: RunId? = null,
        workspace: ExecutionWorkspace? = null,
    ): HostedRun

    /**
     * [open], keyed by each seat's own [HostedAgent.role].
     *
     * The same call for a caller that cannot build a `Map<RoleId, …>` — Swift cannot,
     * because [RoleId] is an inline value class the Objective-C export erases. Two
     * seats declaring the same role is the one new way to get it wrong, and it fails
     * the same way a missing host does.
     */
    suspend fun openSeats(
        roster: Roster,
        seats: List<HostedAgent>,
        goal: Task,
        tools: Set<Tool<*>> = emptySet(),
        policy: RunPolicy = RunPolicy(),
        runId: RunId? = null,
        workspace: ExecutionWorkspace? = null,
    ): HostedRun {
        return open(
            roster = roster,
            seats = seatsByRole(seats),
            goal = goal,
            tools = tools,
            policy = policy,
            runId = runId,
            workspace = workspace,
        )
    }

    /**
     * Carry on a run that held, from its record (AMPR-431, AMPR-385 rows H29–H32).
     *
     * A run whose tool reported its action is waiting on a person ends
     * [RunStatus.Held]; with a [HeldRunStore] its state is recorded before
     * [HostedRun.execute] returns, and [RunResult.resumable] says so. Once the person has
     * decided, the consumer calls this — in the same process or another, minutes or days
     * later — with the same roster, seats filling it, and how the hold was settled:
     *
     * - [HoldResolution.Completed]: the consumer performed the approved action and reports
     *   its result. The held step becomes a success with that result, and the returned
     *   run's [HostedRun.plan] is the approved plan's steps after the held one.
     *   [HostedRun.execute] runs them in order — the first handed the held action's
     *   result — and then carries on exactly as after any cycle: more cycles while
     *   [RunPolicy.cycles] allows, or another hold, which is recorded and resumable again.
     * - [HoldResolution.Declined]: the held step becomes a critical failure whose error
     *   begins `Declined:`, the returned run's plan is empty, [HostedRun.execute] runs and
     *   plans nothing whatever the cap, and the run ends [RunStatus.Failed].
     *
     * Ampere never runs the held tool again: its result comes from [resolution] only.
     * Nothing is perceived or planned again for the held cycle, and the remaining steps are
     * not sent back to [RunPolicy.planGate] — the plan is the one the person approved. A
     * step whose arguments change at dispatch still goes to [PlanGate.reviewChangedStep],
     * as on every dispatch, and consent is still checked at every tool call. RECALL and
     * OBSERVE run again before the remaining steps, and neither calls a model.
     *
     * The run keeps its run id: every event it publishes from here carries the id the run
     * held under, its task is the same task — re-attached, not created again — and
     * [HostedRun.close] is LEARN for the whole run, before the hold and after it. The
     * record is deleted when the resumed run closes in any status but [RunStatus.Held].
     *
     * A hold is resumed at most once. The record is claimed before any seat is built, any
     * event is published or any model is called; a call that loses the claim leaves no
     * trace. If the process dies after the claim, the record stays claimed and listed by
     * [heldRuns], and cannot be resumed again, because some of its remaining steps may
     * have run — its owner decides what to tell the person, and deletes it.
     *
     * The default body throws [HeldRunUnavailableException] with
     * [HeldRunUnavailableException.Why.NoStore]: a host that records nothing has nothing to
     * resume.
     *
     * @param runId The run that held — [RunResult.runId], or [HeldRunSummary.runId].
     * @param roster As for [open]. Its host must be the role that hosted the held run.
     * @param seats As for [open], and validated the same way. They need not be the same
     *   agents, but a seat a remaining step was assigned to should be filled, and a step
     *   whose tool its seat no longer has fails as it would in any run.
     * @param resolution How the hold was settled.
     * @param tools As for [open]: what the remaining steps may dispatch.
     * @param policy As for [open]. Its gate is not asked about the remaining steps; its
     *   cycle cap counts the cycles the run used before it held.
     * @param workspace The checkout the run carries on in (AMPR-496). Null — the default —
     *   is the one the run was opened with, as its record holds it, so a resumed run comes
     *   back to the tree it held in. One given beside a recorded one must equal it, lease
     *   and all: a run's steps so far were made in one checkout, and nothing can reconcile
     *   carrying them on in another. One given for a run that recorded none is the run's
     *   from here on.
     * @throws HeldRunUnavailableException if this host keeps no store
     *   ([NoStore][HeldRunUnavailableException.Why.NoStore]), a run with this id is still
     *   open on it ([StillOpen][HeldRunUnavailableException.Why.StillOpen]), no record is
     *   held under [runId] ([NotHeld][HeldRunUnavailableException.Why.NotHeld]), or the
     *   record was already taken up ([AlreadyClaimed][HeldRunUnavailableException.Why.AlreadyClaimed]).
     * @throws IllegalArgumentException for what [open] refuses, a roster whose host is
     *   not the held run's, or a [workspace] other than the one the run held in — before
     *   the record is claimed.
     */
    suspend fun resume(
        runId: RunId,
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        resolution: HoldResolution,
        tools: Set<Tool<*>> = emptySet(),
        policy: RunPolicy = RunPolicy(),
        workspace: ExecutionWorkspace? = null,
    ): HostedRun = throw HeldRunUnavailableException(runId, HeldRunUnavailableException.Why.NoStore)

    /**
     * [resume], keyed by each seat's own [HostedAgent.role] — for a caller that cannot
     * build a `Map<RoleId, …>`, as [openSeats] is for [open].
     */
    suspend fun resumeSeats(
        runId: RunId,
        roster: Roster,
        seats: List<HostedAgent>,
        resolution: HoldResolution,
        tools: Set<Tool<*>> = emptySet(),
        policy: RunPolicy = RunPolicy(),
        workspace: ExecutionWorkspace? = null,
    ): HostedRun = resume(
        runId = runId,
        roster = roster,
        seats = seatsByRole(seats),
        resolution = resolution,
        tools = tools,
        policy = policy,
        workspace = workspace,
    )

    /**
     * Every held run this host's store records, claimed or not, oldest hold first
     * (AMPR-431).
     *
     * A record with [HeldRunSummary.claimedAt] set was taken up by a resume that has not
     * finished — or never will, because its process died. It cannot be resumed again; its
     * owner decides what to tell the person, and deletes it.
     *
     * Empty for a host that keeps no store, which is the default body.
     */
    suspend fun heldRuns(): List<HeldRunSummary> = emptyList()

    /**
     * Every seat of every run this host currently has open, oldest run first.
     *
     * What `AgentService.listAll` and `AgentService.inspect` report (AMPR-385 row H9):
     * before this they read the roster `AgentService.team {}` was handed, which no
     * agent was ever built from. A run leaves this list when it is closed, so a host
     * with no open run answers empty.
     */
    fun openRunSeats(): List<OpenSeat>
}

/** [seats] keyed by each one's own role, refusing two that declare the same one. */
private fun seatsByRole(seats: List<HostedAgent>): Map<RoleId, HostedAgent> {
    val byRole = seats.groupBy { it.role }
    val duplicated = byRole.filterValues { it.size > 1 }.keys
    require(duplicated.isEmpty()) {
        "one agent fills one seat; duplicated: ${duplicated.joinToString { it.value }}"
    }
    return byRole.mapValues { (_, filling) -> filling.single() }
}

/**
 * A [RunHost] that builds each seat's agent from its [HostedAgent] and runs the
 * roster's plan on them.
 *
 * The shipped implementation. [Ampere.fromEnvironment][link.socket.ampere.api.fromEnvironment]
 * builds one of these; construct your own when composing without an `AmpereInstance`.
 *
 * @param createEventApi The seat doors. Called once per seat per run with that seat's
 *   [HostedAgent.id], so every event a seat publishes is attributed to it and
 *   persisted before it is dispatched (F1). Pass
 *   `EnvironmentService::createEventApi`.
 * @param agentScope The scope the seats' agents publish their spark events and
 *   cognitive snapshots on. The caller's to own — cancelling it stops those
 *   publishes, and a scope that outlives the store they write to is how a closed
 *   driver gets written to. It is *not* the scope a run executes on: that is
 *   whichever scope calls [RunHost.open] and [HostedRun.execute].
 * @param executor What the seats' tool calls dispatch through (AMPR-405). Defaults to
 *   [FunctionExecutor.create], which runs the in-process `FunctionTool`s a consumer's
 *   actions are. Null builds seats that plan and cannot act — a declaration, and one
 *   worth making on purpose.
 * @param heldRuns Where a run that holds is recorded, so [RunHost.resume] can carry it on
 *   (AMPR-431) — `SqlDelightHeldRunStore` over the caller's database, for a hold that has to
 *   outlive the process. Null, the default, records nothing: a held run ends
 *   [RunStatus.Held] all the same, and cannot be resumed.
 * @param goalBranch The Goal branch a run lives on (AMPR-473), built over the host seat's door
 *   — `{ door -> GoalBranch.over(database, door) }`. Called once per opened or resumed run.
 *   With one, a run forms its single root goal held by the host seat and registers every seat
 *   as a Subject before PERCEIVE, and LEARN completes the goal on [RunStatus.Succeeded] and
 *   abandons it on any other end but [RunStatus.Held]. Null, the default, writes nothing to the
 *   branch: no goal, no registration, no extra event.
 * @param field The Field a run may observe (AMPR-480, row N1) — the same
 *   [FieldService][link.socket.ampere.api.service.FieldService] an `AmpereInstance` exposes as
 *   `field`, built over the caller's database. Read only when a run's
 *   [RunPolicy.observe] asks for a [FieldScope] other than [FieldScope.Off]: then the run
 *   takes one snapshot as the host seat inside PERCEIVE, renders it into that seat's PERCEIVE
 *   prompt, and takes one more inside OBSERVE after each executed cycle. Nothing is written
 *   and no event is added. Null, the default, is a host whose runs never read the Field,
 *   whatever their policy says — byte-identical to a host from before the slot existed.
 */
@AmpereStableApi
@OptIn(AmpereExperimentalApi::class)
fun rosterRunHost(
    createEventApi: (AgentId) -> AgentEventApi,
    agentScope: CoroutineScope,
    executor: Executor? = FunctionExecutor.create(),
    heldRuns: HeldRunStore? = null,
    goalBranch: ((AgentEventApi) -> GoalBranch)? = null,
    field: FieldService? = null,
): RunHost = RosterRunHost(
    createEventApi = createEventApi,
    agentScope = agentScope,
    executor = executor,
    heldRuns = heldRuns,
    goalBranch = goalBranch,
    field = field,
)
