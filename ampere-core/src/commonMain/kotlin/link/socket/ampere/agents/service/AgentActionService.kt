package link.socket.ampere.agents.service

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.api.AgentEventApi

/**
 * Service for agent-related actions.
 *
 * One action, and it is dormant: see [wakeAgent].
 */
class AgentActionService(
    private val eventApi: AgentEventApi,
) {
    /**
     * Publish a `TaskCreated` addressed to [agentId]. **Nothing wakes (AMPR-399).**
     *
     * There is no `AgentWakeRequested` event, so this borrows `TaskCreated` as a signal —
     * but no agent-side handler turns a task event into a perceive-reason-act cycle. Since
     * AMPR-404 the event reaches an agent registered through
     * `EnvironmentService.routeEventsToAgent` as a `NotificationEvent.ToAgent`; a
     * notification is a persisted row, not a cycle, so a dormant agent stays dormant.
     *
     * The publish itself is real: the event is persisted and dispatched through the door, so
     * a consumer driving its own agents off the bus can use this as a publish helper.
     *
     * @return success once the event is on the record, failure if the publish threw
     */
    @Deprecated(
        message = "wakeAgent wakes nothing: it publishes one TaskCreated for the agent and " +
            "no handler turns that into a cycle (AMPR-399). This is re-pointed at RunHost " +
            "when AMPR-393 ships.",
    )
    suspend fun wakeAgent(agentId: AgentId): Result<Unit> {
        return try {
            // Emit a low-priority task event that will wake the agent
            eventApi.publishTaskCreated(
                taskId = "wake-$agentId-${Clock.System.now().toEpochMilliseconds()}",
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
