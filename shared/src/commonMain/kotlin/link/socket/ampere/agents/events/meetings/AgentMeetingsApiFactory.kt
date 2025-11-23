package link.socket.ampere.agents.events.meetings

import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

class AgentMeetingsApiFactory(
    private val meetingBuilder: MeetingBuilder,
    private val meetingOrchestrator: MeetingOrchestrator,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /** Create an [AgentMeetingsApi] for the given [agentId]. */
    fun create(agentId: AgentId): AgentMeetingsApi = AgentMeetingsApi(
        agentId = agentId,
        meetingBuilder = meetingBuilder,
        meetingOrchestrator = meetingOrchestrator,
        logger = logger,
    )
}
