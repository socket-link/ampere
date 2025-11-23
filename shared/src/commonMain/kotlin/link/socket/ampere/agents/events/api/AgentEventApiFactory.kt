package link.socket.ampere.agents.events.api

import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventBus
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/** Factory to create [AgentEventApi] instances wired to a persistent EventBus. */
class AgentEventApiFactory(
    private val eventRepository: EventRepository,
    private val eventBus: EventBus,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /**
     * Create an [AgentEventApi] for the given [agentId].
     */
    fun create(agentId: AgentId): AgentEventApi =
        AgentEventApi(
            agentId = agentId,
            eventRepository = eventRepository,
            eventBus = eventBus,
            logger = logger,
        )
}
