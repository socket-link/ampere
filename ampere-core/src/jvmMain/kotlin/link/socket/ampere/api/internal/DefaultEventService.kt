package link.socket.ampere.api.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.api.service.EventService

internal class DefaultEventService(
    private val eventRelayService: EventRelayService,
    private val eventRepository: EventRepository,
) : EventService {

    override fun observe(filters: EventRelayFilters): Flow<Event> =
        eventRelayService.subscribeToLiveEvents(filters)

    override suspend fun query(
        fromTime: Instant,
        toTime: Instant,
        sourceIds: Set<String>?,
    ): Result<List<Event>> = eventRepository.getEventsWithFilters(
        fromTime = fromTime,
        toTime = toTime,
        sourceIds = sourceIds,
    )

    override fun replay(
        from: Instant,
        to: Instant,
        filters: EventRelayFilters,
    ): Flow<Event> {
        // EventRelayService.replayEvents is suspend, so we bridge to Flow via channelFlow.
        return kotlinx.coroutines.flow.channelFlow {
            val result = eventRelayService.replayEvents(from, to, filters)
            result.getOrNull()?.collect { event ->
                send(event)
            }
        }
    }
}
