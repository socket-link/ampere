package link.socket.ampere.agents.events.api

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/** Factory to create [AgentEventApi] instances wired to a persistent EventBus. */
class AgentEventApiFactory(
    private val eventRepository: EventRepository,
    private val eventSerialBus: EventSerialBus,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /**
     * Create an [AgentEventApi] for the given [agentId].
     */
    fun create(agentId: AgentId): AgentEventApi =
        AgentEventApi(
            agentId = agentId,
            eventRepository = eventRepository,
            eventSerialBus = eventSerialBus,
            logger = logger,
        )
}
