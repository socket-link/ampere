package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Level of system health.
 */
@Serializable
enum class HealthLevel {
    Healthy,
    Degraded,
    Unhealthy,
}

/**
 * Point-in-time health status of the AMPERE system.
 *
 * ```
 * ampere.status.health().collect { health ->
 *     if (health.overall != HealthLevel.Healthy) {
 *         println("Issues: ${health.issues.joinToString()}")
 *     }
 * }
 * ```
 */
@Serializable
data class HealthStatus(
    val overall: HealthLevel,
    val activeAgents: Int,
    val idleAgents: Int,
    val pendingTickets: Int,
    val issues: List<String> = emptyList(),
)
