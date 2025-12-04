package link.socket.ampere.agents.events.meetings

import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

class AgentMeetingsApi(
    val agentId: AgentId,
    private val meetingBuilder: MeetingBuilder,
    private val meetingOrchestrator: MeetingOrchestrator,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    // TODO: Improve mapping logic
    suspend fun createMeeting(
        onMeetingCreated: suspend (Meeting) -> Unit = {},
        builder: MeetingBuilder.() -> MeetingBuilder,
    ) {
        builder(meetingBuilder)
            .buildMeeting()
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to build meeting",
                    throwable = throwable,
                )
            }
            .map { meeting ->
                meetingOrchestrator.scheduleMeeting(
                    meeting = meeting,
                    scheduledBy = EventSource.Agent(agentId),
                )
            }
            .onFailure {
                logger.logError(
                    message = "Failed to schedule meeting",
                    throwable = it,
                )
            }
            .getOrNull()
            ?.getOrNull()
            ?.let { createdMeeting ->
                onMeetingCreated(createdMeeting)
            }
    }
}
