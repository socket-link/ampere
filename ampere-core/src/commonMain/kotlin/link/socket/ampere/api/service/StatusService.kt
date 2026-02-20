package link.socket.ampere.api.service

import link.socket.ampere.api.model.SystemSnapshot

/**
 * SDK service for system-wide status and health.
 *
 * Maps to CLI command: `status`
 */
interface StatusService {

    /**
     * Get a complete system snapshot.
     *
     * ```
     * val status = ampere.status.snapshot()
     * println("${status.activeTickets} active tickets")
     * ```
     */
    suspend fun snapshot(): Result<SystemSnapshot>
}
