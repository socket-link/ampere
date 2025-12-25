package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant

/**
 * System-wide vitals for dashboard display.
 */
data class SystemVitals(
    val activeAgentCount: Int = 0,
    val systemState: SystemState = SystemState.IDLE,
    val lastSignificantEventTime: Instant? = null
)

/**
 * Overall system state.
 */
enum class SystemState {
    IDLE,
    WORKING,
    ATTENTION_NEEDED
}
