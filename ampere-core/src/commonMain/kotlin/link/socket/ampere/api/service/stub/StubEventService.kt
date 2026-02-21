package link.socket.ampere.api.service.stub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.api.service.EventService

/**
 * Stub implementation of [EventService] for testing and parallel development.
 *
 * Returns empty flows and empty query results.
 */
class StubEventService : EventService {

    override fun observe(filters: EventRelayFilters): Flow<Event> = emptyFlow()

    override suspend fun query(
        fromTime: Instant,
        toTime: Instant,
        sourceIds: Set<String>?,
    ): Result<List<Event>> = Result.success(emptyList())

    override fun replay(
        from: Instant,
        to: Instant,
        filters: EventRelayFilters,
    ): Flow<Event> = emptyFlow()
}
