package link.socket.ampere.api.internal

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

internal class DefaultAgentService(
    private val agentActionService: AgentActionService,
    private val eventApi: AgentEventApi,
) : AgentService {

    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam =
        AgentTeam.create(configure)

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
}
