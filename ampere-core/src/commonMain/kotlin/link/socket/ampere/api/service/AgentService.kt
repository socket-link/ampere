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
 * ```
 * ampere.agents.team {
 *     agent(ProductManager) { personality { directness = 0.8 } }
 *     agent(Engineer) { personality { creativity = 0.7 } }
 *     agent(QATester)
 * }
 *
 * ampere.agents.pursue("Build authentication system")
 * ```
 */
@link.socket.ampere.api.AmpereStableApi
interface AgentService {

    /**
     * Create an agent team using the builder DSL.
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
     * Give the current team a goal to pursue.
     * This is the primary "start working" operation.
     * Returns immediately; work proceeds asynchronously.
     * Observe progress via [EventService.observe].
     *
     * ```
     * val goalId = ampere.agents.pursue("Add retry logic to payment auth")
     * ```
     *
     * @param goal High-level description of what to accomplish
     * @return Result containing a goal ID for tracking
     */
    suspend fun pursue(goal: String): Result<String>

    /**
     * Wake a dormant agent, making it available for work.
     *
     * ```
     * ampere.agents.wake("reviewer-agent")
     * ```
     *
     * @param agentId The ID of the agent to wake
     */
    suspend fun wake(agentId: AgentId): Result<Unit>

    /**
     * Get the current state of a specific agent.
     *
     * ```
     * val agent = ampere.agents.inspect("engineer-agent")
     * println("${agent.role} is ${agent.state}")
     * ```
     *
     * @param agentId The ID of the agent to inspect
     */
    suspend fun inspect(agentId: AgentId): Result<AgentSnapshot>

    /**
     * List all agents and their current states.
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
