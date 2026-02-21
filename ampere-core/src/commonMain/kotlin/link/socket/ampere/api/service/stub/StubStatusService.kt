package link.socket.ampere.api.service.stub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import link.socket.ampere.api.model.HealthLevel
import link.socket.ampere.api.model.HealthStatus
import link.socket.ampere.api.model.SystemSnapshot
import link.socket.ampere.api.service.StatusService

/**
 * Stub implementation of [StatusService] for testing and parallel development.
 *
 * Returns zeroed snapshot and healthy status.
 */
class StubStatusService : StatusService {

    override suspend fun snapshot(): Result<SystemSnapshot> =
        Result.success(
            SystemSnapshot(
                agents = emptyList(),
                activeTickets = 0,
                totalTickets = 0,
                activeThreads = 0,
                totalMessages = 0,
                escalatedThreads = 0,
                workspace = null,
            )
        )

    override fun health(): Flow<HealthStatus> = flowOf(
        HealthStatus(
            overall = HealthLevel.Healthy,
            activeAgents = 0,
            idleAgents = 0,
            pendingTickets = 0,
        )
    )
}
