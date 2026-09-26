package link.socket.ampere.api.service.stub

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

/**
 * Stub implementation of [AgentService] for testing and parallel development.
 *
 * Returns sensible defaults without requiring real infrastructure.
 */
class StubAgentService : AgentService {

    private var goalCounter = 0
    private var currentTeam: AgentTeam? = null

    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam =
        AgentTeam.create(configure).also { currentTeam = it }

    override suspend fun pursue(goal: String): Result<String> {
        goalCounter++
        return Result.success("stub-goal-$goalCounter")
    }

    override suspend fun wake(agentId: AgentId): Result<Unit> =
        Result.success(Unit)

    override suspend fun inspect(agentId: AgentId): Result<AgentSnapshot> =
        Result.success(
            AgentSnapshot(
                id = agentId,
                role = agentId,
                state = AgentState.Idle,
                currentTask = null,
                sparkStack = emptyList(),
                lastActivity = Clock.System.now(),
            ),
        )

    override suspend fun listAll(): List<AgentSnapshot> = emptyList()

    /**
     * Checked against the team from [team] so the stub agrees with the documented
     * contract: an unknown agent or a missing team is a failure, not a silent success.
     * The snapshots from [inspect] and [listAll] stay static, as elsewhere in this stub.
     */
    override suspend fun pause(agentId: AgentId): Result<Unit> {
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured. Call team {} first."),
        )
        if (!team.pauseMember(agentId)) {
            return Result.failure(IllegalArgumentException("Agent not found: $agentId"))
        }
        return Result.success(Unit)
    }
}
