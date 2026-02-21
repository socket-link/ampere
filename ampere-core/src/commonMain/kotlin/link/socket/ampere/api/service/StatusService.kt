package link.socket.ampere.api.service

import kotlinx.coroutines.flow.Flow
import link.socket.ampere.api.model.HealthStatus
import link.socket.ampere.api.model.SystemSnapshot

/**
 * SDK service for system-wide status and health.
 *
 * Maps to CLI command: `status`
 *
 * ```
 * val status = ampere.status.snapshot()
 * println("${status.agents.size} agents, ${status.activeTickets} active tickets")
 *
 * ampere.status.health().collect { health ->
 *     println("System is ${health.overall}")
 * }
 * ```
 */
interface StatusService {

    /**
     * Get a complete system snapshot.
     *
     * ```
     * val status = ampere.status.snapshot()
     * println("${status.activeTickets} active tickets")
     * println("${status.agents.size} agents online")
     * ```
     */
    suspend fun snapshot(): Result<SystemSnapshot>

    /**
     * Observe system health as a continuous flow.
     *
     * Emits a new [HealthStatus] whenever a meaningful state change
     * occurs (agent state transitions, ticket completions, errors).
     *
     * ```
     * ampere.status.health().collect { health ->
     *     if (health.overall == HealthLevel.Degraded) {
     *         alert("System degraded: ${health.issues}")
     *     }
     * }
     * ```
     *
     * @return Hot flow of health status updates
     */
    fun health(): Flow<HealthStatus>
}
