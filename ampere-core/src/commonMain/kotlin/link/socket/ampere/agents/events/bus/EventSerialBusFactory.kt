package link.socket.ampere.agents.events.bus

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/**
 * Factory for creating an [EventSerialBus]. Persistence is handled by higher-level APIs.
 */
class EventSerialBusFactory(
    private val scope: CoroutineScope,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /**
     * Create a new [EventSerialBus].
     */
    fun create(): EventSerialBus =
        EventSerialBus(
            scope = scope,
            logger = logger,
        )
}
