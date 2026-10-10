// The deprecated AgentTeam surface (AMPR-399) is named throughout this file — in the
// imports, in the deprecated members and in their return types — so the warning is
// suppressed for the whole file rather than nine times inside it.
@file:Suppress("DEPRECATION")

package link.socket.ampere.api.service

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

/**
 * SDK service for agent lifecycle and team management.
 *
 * Maps to CLI commands: `run --goal`, `agent wake`, `status` (agent portion)
 *
 * ### The team-and-goal surface does not run work (AMPR-399)
 *
 * [team], [pursue] and [wake] are deprecated because none of them starts a run. The
 * roster [team] builds is a DSL value that no agent is constructed from; [pursue] and
 * [wake] publish one `TaskCreated` each and nothing in AMPERE opens a run from a task
 * event. Each method's own KDoc states exactly what it does today.
 *
 * What remains truthful on this service is read-only: [inspect] and [listAll] report the
 * roster [team] was given, and [pause] changes what they report. A hosted run — the entry
 * point that will actually charge PERCEIVE → … → LEARN for a goal — arrives with
 * `RunHost` (AMPR-393), and these methods are re-pointed at it then.
 *
 * Until then, the paths that do run agents are the CLI (`ampere --goal`, `ampere --issues`)
 * and `AmpereRuntime`.
 *
 * ```
 * val agents = ampere.agents.listAll()
 * agents.forEach { println("${it.role}: ${it.state}") }
 * ```
 */
@link.socket.ampere.api.AmpereStableApi
interface AgentService {

    /**
     * Create an agent team using the builder DSL.
     *
     * **This does nothing beyond building a value.** [AgentTeam] is a DSL projection: it
     * holds the roles you declared and a replay buffer of UI markers. No agent is
     * instantiated from it, no spark stack is built, nothing is scheduled, and
     * [AgentTeam.pursue] ends in a `TODO` rather than delegating to anything. The only
     * observable effect of calling this method is that [inspect], [listAll] and [pause]
     * start reporting the roles you declared.
     *
     * ```
     * ampere.agents.team {
     *     agent(ProductManager) { personality { directness = 0.8 } }
     *     agent(Engineer) { personality { creativity = 0.7 } }
     *     agent(QATester)
     * }
     * ```
     */
    @Deprecated(
        message = "team {} builds a roster that nothing runs: no agent is constructed from " +
            "it and AgentTeam.pursue only emits UI markers. Run agents through the CLI or " +
            "AmpereRuntime; this method is re-pointed at RunHost when AMPR-393 ships.",
    )
    fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam

    /**
     * Give the current team a goal to pursue.
     *
     * **This starts no work.** It publishes one `Event.TaskCreated` with
     * `assignedTo = null` through the event door and returns that task's id. No agent
     * perceives, plans or executes as a result: since AMPR-404 the event does reach any
     * agent registered through `EnvironmentService.routeEventsToAgent` as a
     * `NotificationEvent.ToAgent`, but nothing in AMPERE turns such a notification into a
     * run. The returned id names a task that stays `Pending` forever, and
     * [EventService.observe] shows the `TaskCreated` and nothing after it.
     *
     * The id is still a real event id, so a consumer that drives its own agents off the
     * bus can use this as a publish helper — knowing that is all it is.
     *
     * ```
     * val goalId = ampere.agents.pursue("Add retry logic to payment auth").getOrThrow()
     * ```
     *
     * @param goal High-level description of what to accomplish
     * @return Result containing the id of the published task
     */
    @Deprecated(
        message = "pursue starts no work: it publishes one TaskCreated(assignedTo = null) " +
            "that nothing consumes as work. Drive a goal through the CLI or AmpereRuntime; " +
            "this method is re-pointed at RunHost when AMPR-393 ships.",
    )
    suspend fun pursue(goal: String): Result<String>

    /**
     * Wake a dormant agent, making it available for work.
     *
     * **This wakes nothing.** Like [pursue], it publishes one `Event.TaskCreated` — this
     * one carrying `assignedTo = agentId` — and returns success once that event is on the
     * record. There is no `AgentWakeRequested` event and no agent-side handler that turns
     * a task event into a perceive-reason-act cycle, so a dormant agent stays dormant and
     * [inspect] reports the same state after the call as before it.
     *
     * ```
     * ampere.agents.wake("reviewer-agent")
     * ```
     *
     * @param agentId The ID of the agent to wake
     */
    @Deprecated(
        message = "wake wakes nothing: it publishes one TaskCreated for the agent and no " +
            "handler turns that into a cycle. This method is re-pointed at RunHost when " +
            "AMPR-393 ships.",
    )
    suspend fun wake(agentId: AgentId): Result<Unit>

    /**
     * Get the current state of a specific agent.
     *
     * Reads the roster given to [team], so it fails until a team has been configured and
     * `currentTask` is always `null`: nothing on this service runs a task. `RunHost`
     * (AMPR-393) makes this read the seats of open runs instead.
     *
     * ```
     * val agent = ampere.agents.inspect("engineer-agent")
     * println("${agent.role} is ${agent.state}")
     * ```
     *
     * @param agentId The ID of the agent to inspect
     * @return [Result.failure] if no team has been configured, or if [agentId] does not
     *   name a member of the current team
     */
    suspend fun inspect(agentId: AgentId): Result<AgentSnapshot>

    /**
     * List all agents and their current states.
     *
     * Reads the roster given to [team], so it is empty until a team has been configured —
     * which is why `StatusService.health()` reports `Unhealthy` ("No agents configured")
     * on a fresh instance. Every snapshot carries `currentTask = null`, as in [inspect].
     * `RunHost` (AMPR-393) makes this list the seats of open runs instead.
     *
     * ```
     * val agents = ampere.agents.listAll()
     * agents.forEach { println("${it.role}: ${it.state}") }
     * ```
     */
    suspend fun listAll(): List<AgentSnapshot>

    /**
     * Stop one running agent gracefully, leaving the rest of the team running.
     *
     * The paused agent reports [AgentState.Paused] from [inspect] and [listAll] until it
     * is resumed through the team ([AgentTeam.resumeMember]).
     *
     * What this pauses today is the *report*, not a running agent: nothing on this service
     * executes, so there is no cycle to interrupt. It is listed here rather than
     * deprecated because its contract — which agent [inspect] and [listAll] call paused,
     * and a failure rather than a silent success for an unknown agent — holds as written,
     * and `RunHost` (AMPR-393) gives it a seat to actually pause.
     *
     * ```
     * ampere.agents.pause(Engineer.name)
     * ```
     *
     * @param agentId The ID of the agent to pause, which must name a member of the
     *   current team
     * @return [Result.failure] if no team has been configured, or if [agentId] does not
     *   name a member of the current team. Nothing is paused in either case.
     */
    suspend fun pause(agentId: AgentId): Result<Unit>
}
