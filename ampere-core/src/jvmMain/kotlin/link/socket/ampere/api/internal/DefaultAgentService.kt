package link.socket.ampere.api.internal

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

internal class DefaultAgentService(
    private val agentActionService: AgentActionService,
    private val eventApi: AgentEventApi,
) : AgentService {

    private var currentTeam: AgentTeam? = null

    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam {
        val team = AgentTeam.create(configure)
        currentTeam = team
        return team
    }

    override suspend fun pursue(goal: String): Result<String> {
        return try {
            val taskId = "goal-${Clock.System.now().toEpochMilliseconds()}"
            eventApi.publishTaskCreated(
                taskId = taskId,
                urgency = Urgency.HIGH,
                description = goal,
                assignedTo = null,
            )
            Result.success(taskId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun wake(agentId: AgentId): Result<Unit> =
        agentActionService.wakeAgent(agentId)

    override suspend fun inspect(agentId: AgentId): Result<AgentSnapshot> {
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured. Call team {} first.")
        )
        val member = team.getMembers().find { it.role == agentId }
            ?: return Result.failure(IllegalArgumentException("Agent not found: $agentId"))

        return Result.success(
            AgentSnapshot(
                id = agentId,
                role = member.role,
                state = if (member.isActive) AgentState.Active else AgentState.Idle,
                currentTask = null,
                sparkStack = member.capabilities,
                lastActivity = Clock.System.now(),
            )
        )
    }

    override suspend fun listAll(): List<AgentSnapshot> {
        val team = currentTeam ?: return emptyList()
        return team.getMembers().map { member ->
            AgentSnapshot(
                id = member.role,
                role = member.role,
                state = if (member.isActive) AgentState.Active else AgentState.Idle,
                currentTask = null,
                sparkStack = member.capabilities,
                lastActivity = Clock.System.now(),
            )
        }
    }

    override suspend fun pause(agentId: AgentId): Result<Unit> {
        return try {
            currentTeam?.pause()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
