package link.socket.ampere.api.service.stub

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder
import kotlinx.datetime.Clock

/**
 * Stub implementation of [AgentService] for testing and parallel development.
 *
 * Returns sensible defaults without requiring real infrastructure.
 */
class StubAgentService : AgentService {

    private var goalCounter = 0

    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam =
        AgentTeam.create(configure)

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
            )
        )

    override suspend fun listAll(): List<AgentSnapshot> = emptyList()

    override suspend fun pause(agentId: AgentId): Result<Unit> =
        Result.success(Unit)
}
