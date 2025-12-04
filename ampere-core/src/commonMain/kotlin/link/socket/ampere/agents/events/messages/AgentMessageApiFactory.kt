package link.socket.ampere.agents.events.messages

import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.events.bus.EventSerialBus

class AgentMessageApiFactory(
    private val messageRepository: MessageRepository,
    private val eventSerialBus: EventSerialBus,
) {
    /**
     * Create an [AgentMessageApi] for the given [agentId].
     */
    fun create(agentId: AgentId): AgentMessageApi =
        AgentMessageApi(
            agentId = agentId,
            messageRepository = messageRepository,
            eventSerialBus = eventSerialBus,
        )
}
