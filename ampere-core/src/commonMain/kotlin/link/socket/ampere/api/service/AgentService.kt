package link.socket.ampere.api.service

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

/**
 * SDK service for agent lifecycle and team management.
 *
 * Maps to CLI commands: `run --goal`, `agent wake`, `status` (agent portion)
 */
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
     * ampere.agents.pursue("Add retry logic to payment auth")
     * ```
     */
    suspend fun pursue(goal: String): Result<String>

    /**
     * Wake a dormant agent, making it available for work.
     *
     * ```
     * ampere.agents.wake("reviewer-agent")
     * ```
     */
    suspend fun wake(agentId: AgentId): Result<Unit>
}
