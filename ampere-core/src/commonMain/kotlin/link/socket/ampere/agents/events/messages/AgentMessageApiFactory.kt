package link.socket.ampere.agents.events.messages

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.api.AgentEventApiFactory

/** Factory to create [AgentMessageApi] instances that publish through a per-agent door. */
class AgentMessageApiFactory(
    private val messageRepository: MessageRepository,
    private val eventApiFactory: AgentEventApiFactory,
) {
    /**
     * Create an [AgentMessageApi] for the given [agentId], publishing through
     * `eventApiFactory.create(agentId)`.
     */
    fun create(agentId: AgentId): AgentMessageApi =
        AgentMessageApi(
            agentId = agentId,
            messageRepository = messageRepository,
            eventApi = eventApiFactory.create(agentId),
        )
}
