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
 * ### The team-and-goal surface runs a hosted run (AMPR-393, row H13)
 *
 * [team] declares the roster and [pursue] opens a run over it: PERCEIVE → RECALL →
 * OBSERVE → PLAN → EXECUTE → LEARN on
 * [AmpereInstance.runs][link.socket.ampere.api.AmpereInstance.runs], every event
 * through its seats' own doors under one run id. Before AMPR-393 neither did anything
 * — the roster was a DSL value no agent was built from, and `pursue` published one
 * `TaskCreated` that nothing consumed as work (AMPR-399).
 *
 * [inspect] and [listAll] report the seats of the runs this instance has open, and
 * fall back to the declared roster when it has none. [wake] is still deprecated:
 * a run is opened for a goal, so there is no "wake this agent" to re-point it at.
 *
 * ```
 * ampere.agents.team {
 *     agent(ProductManager)
 *     agent(Engineer)
 * }
 * val runId = ampere.agents.pursue("Add retry logic to payment auth").getOrThrow()
 *
 * val agents = ampere.agents.listAll()
 * agents.forEach { println("${it.role}: ${it.state}") }
 * ```
 */
@link.socket.ampere.api.AmpereStableApi
interface AgentService {

    /**
     * Declare the roster [pursue] runs over, using the builder DSL.
     *
     * Declaring is all it does — no agent is built here, and no model is called. The
     * roles become a [link.socket.ampere.roster.RosterConfig]
     * ([AgentTeam.roster]) whose host is the member holding
     * `Capability.DELEGATION`, or the first one; [pursue] builds one seat per role
     * and opens a run over them.
     *
     * A DSL role declares capabilities, not tool ids, so a run over this roster plans
     * and reasons and dispatches no tool. Author a `RosterConfig` and open the run
     * through [link.socket.ampere.api.AmpereInstance.runs] directly to give seats
     * tools.
     *
     * ```
     * ampere.agents.team {
     *     agent(ProductManager) { personality { directness = 0.8 } }
     *     agent(Engineer) { personality { creativity = 0.7 } }
     *     agent(QATester)
     * }
     * ```
     */
    fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam

    /**
     * Give the current team a goal to pursue: one hosted run, start to finish.
     *
     * Opens a run over [AgentTeam.roster] through
     * [AmpereInstance.runs][link.socket.ampere.api.AmpereInstance.runs], executes it
     * and closes it — so it suspends for the whole run, which is as long as the
     * model calls take, and the run id it returns names a run whose every phase is on
     * the record. This is AMPR-393's re-point: before it, the call published one
     * `TaskCreated(assignedTo = null)` that nothing consumed as work (AMPR-399).
     *
     * The run uses the default [link.socket.ampere.propel.RunPolicy]: one cycle, a
     * LEARN that bills nothing, and no plan gate. A consumer that wants a gate,
     * several cycles, or tools goes through
     * [AmpereInstance.runs][link.socket.ampere.api.AmpereInstance.runs] instead —
     * this method is the one-line path, not the configurable one.
     *
     * ```
     * val runId = ampere.agents.pursue("Add retry logic to payment auth").getOrThrow()
     * ampere.events.query(...)   // every phase of runId
     * ```
     *
     * @param goal High-level description of what to accomplish
     * @return the run id, or a failure when no team has been declared, when this
     *   instance has no host, or when it carries no `UpstreamLlmClient` — a seat with
     *   no transport cannot make the call PERCEIVE is, and a transport is opted into
     *   rather than inherited (AMPR-236)
     */
    suspend fun pursue(goal: String): Result<String>

    /**
     * Wake a dormant agent, making it available for work.
     *
     * **This wakes nothing.** It publishes one `Event.TaskCreated` carrying
     * `assignedTo = agentId` and returns success once that event is on the record. There
     * is no `AgentWakeRequested` event and no agent-side handler that turns a task event
     * into a perceive-reason-act cycle, so a dormant agent stays dormant and [inspect]
     * reports the same state after the call as before it. A seat exists for the length of
     * a run, so there is nothing dormant for this to wake: [pursue] opens the run.
     *
     * ```
     * ampere.agents.wake("reviewer-agent")
     * ```
     *
     * @param agentId The ID of the agent to wake
     */
    @Deprecated(
        message = "wake wakes nothing: it publishes one TaskCreated for the agent and no " +
            "handler turns that into a cycle. A RunHost seat exists for the length of a " +
            "run, so there is nothing dormant to wake: use pursue, or AmpereInstance.runs " +
            "(AMPR-393).",
        replaceWith = ReplaceWith("pursue(goal)"),
    )
    suspend fun wake(agentId: AgentId): Result<Unit>

    /**
     * Get the current state of a specific agent.
     *
     * Reads the seats of the runs this instance has open, and falls back to the roster
     * given to [team] when it has none. A seat reports `Active` with the run's goal as
     * its `currentTask`; a declared role nobody is filling reports what [team]'s flags
     * say about it, with no task.
     *
     * ```
     * val agent = ampere.agents.inspect("engineer-agent")
     * println("${agent.role} is ${agent.state}")
     * ```
     *
     * @param agentId The agent id of an open seat, or the role name of a declared
     *   member
     * @return [Result.failure] if there is no open run and no team has been configured,
     *   or if [agentId] names neither an open seat nor a member of the current team
     */
    suspend fun inspect(agentId: AgentId): Result<AgentSnapshot>

    /**
     * List all agents and their current states.
     *
     * The seats of every run this instance has open, oldest run first; the roster given
     * to [team] when it has none. Empty until one or the other exists — which is why
     * `StatusService.health()` reports `Unhealthy` ("No agents configured") on a fresh
     * instance.
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
     * What this pauses is the *report*, not a run in flight: a hosted run is driven by
     * the scope that called it, so stopping one is cancelling that scope, not a flag on
     * this service. Its contract — which agent [inspect] and [listAll] call paused, and
     * a failure rather than a silent success for an unknown agent — holds as written.
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
