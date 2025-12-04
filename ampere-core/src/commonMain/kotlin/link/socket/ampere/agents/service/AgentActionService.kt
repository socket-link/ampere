package link.socket.ampere.agents.service

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.api.AgentEventApi

/**
 * Service for agent-related actions.
 */
class AgentActionService(
    private val eventApi: AgentEventApi,
) {
    /**
     * Send a wake signal to an agent by publishing a TaskCreated event.
     *
     * This triggers the agent's perceive-reason-act cycle,
     * causing it to check for new work and decide what to do.
     *
     * Note: Since there's no specific AgentWakeRequested event yet,
     * we use TaskCreated as a wake-up mechanism.
     */
    suspend fun wakeAgent(agentId: AgentId): Result<Unit> {
        return try {
            // Emit a low-priority task event that will wake the agent
            eventApi.publishTaskCreated(
                taskId = "wake-${agentId}-${Clock.System.now().toEpochMilliseconds()}",
                urgency = Urgency.LOW,
                description = "Manual wake signal from CLI",
                assignedTo = agentId,
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
