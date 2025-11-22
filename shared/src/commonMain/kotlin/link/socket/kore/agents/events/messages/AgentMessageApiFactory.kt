package link.socket.kore.agents.events.messages

import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.events.bus.EventBus

class AgentMessageApiFactory(
    private val messageRepository: MessageRepository,
    private val eventBus: EventBus,
) {
    /**
     * Create an [AgentMessageApi] for the given [agentId].
     */
    fun create(agentId: AgentId): AgentMessageApi =
        AgentMessageApi(
            agentId = agentId,
            messageRepository = messageRepository,
            eventBus = eventBus,
        )
}
