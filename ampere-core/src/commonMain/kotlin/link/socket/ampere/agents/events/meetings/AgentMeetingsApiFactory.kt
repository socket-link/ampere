package link.socket.ampere.agents.events.meetings

import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

class AgentMeetingsApiFactory(
    private val meetingOrchestrator: MeetingOrchestrator,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /** Create an [AgentMeetingsApi] for the given [agentId]. */
    fun create(agentId: AgentId): AgentMeetingsApi = AgentMeetingsApi(
        agentId = agentId,
        meetingBuilder = MeetingBuilder(agentId),
        meetingOrchestrator = meetingOrchestrator,
        logger = logger,
    )
}
