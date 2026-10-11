package link.socket.ampere.propel

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.AmpereStableApi
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
     * @throws IllegalArgumentException if no seat fills the roster's host, if a seat
     *   names a role the roster does not hold, or if two seats share an agent id.
     */
    suspend fun open(
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        goal: Task,
        tools: Set<Tool<*>> = emptySet(),
        policy: RunPolicy = RunPolicy(),
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
    ): HostedRun {
        val byRole = seats.groupBy { it.role }
        val duplicated = byRole.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "one agent fills one seat; duplicated: ${duplicated.joinToString { it.value }}"
        }
        return open(roster, byRole.mapValues { (_, filling) -> filling.single() }, goal, tools, policy)
    }

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
 */
@AmpereStableApi
fun rosterRunHost(
    createEventApi: (AgentId) -> AgentEventApi,
    agentScope: CoroutineScope,
    executor: Executor? = FunctionExecutor.create(),
): RunHost = RosterRunHost(
    createEventApi = createEventApi,
    agentScope = agentScope,
    executor = executor,
)
