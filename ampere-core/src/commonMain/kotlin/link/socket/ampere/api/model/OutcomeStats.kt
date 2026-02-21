package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Aggregate statistics about execution outcomes.
 */
@Serializable
data class OutcomeStats(
    val totalOutcomes: Int,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Double,
    val averageDurationMs: Long,
)
