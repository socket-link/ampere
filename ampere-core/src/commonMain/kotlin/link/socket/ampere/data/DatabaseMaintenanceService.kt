package link.socket.ampere.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.utils.EventLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Configuration for database maintenance operations.
 *
 * @property eventRetentionDays Number of days to retain events (default: 30)
 * @property messageRetentionDays Number of days to retain messages (default: 90)
 * @property outcomeRetentionDays Number of days to retain outcomes (default: 180, 6 months)
 * @property maxEventsToKeep Maximum number of events to keep regardless of age (default: 50000)
 * @property maxOutcomesToKeep Maximum number of outcomes to keep regardless of age (default: 10000)
 * @property runVacuum Whether to run VACUUM after cleanup (default: true, but slow)
 */
data class DatabaseMaintenanceConfig(
    val eventRetentionDays: Int = 30,
    val messageRetentionDays: Int = 90,
    val outcomeRetentionDays: Int = 180,
    val maxEventsToKeep: Long = 50000,
    val maxOutcomesToKeep: Long = 10000,
    val runVacuum: Boolean = true,
)

/**
 * Service responsible for maintaining database health through cleanup operations.
 *
 * This service:
 * - Deletes old events, messages, and outcomes based on retention policies
 * - Runs VACUUM to reclaim disk space
 * - Logs cleanup statistics
 *
 * Cleanup can be run:
 * - Manually via [runCleanup]
 * - Automatically on initialization if configured
 *
 * @property eventRepository Repository for event cleanup operations
 * @property messageRepository Repository for message cleanup operations
 * @property outcomeMemoryRepository Repository for outcome cleanup operations
 * @property config Retention policy configuration
 * @property scope Coroutine scope for async operations
 * @property logger Logger for cleanup operations
 */
class DatabaseMaintenanceService(
    private val eventRepository: EventRepository,
    private val messageRepository: MessageRepository,
    private val outcomeMemoryRepository: OutcomeMemoryRepository,
    private val config: DatabaseMaintenanceConfig = DatabaseMaintenanceConfig(),
    private val scope: CoroutineScope,
    private val logger: EventLogger? = null,
) {
    /**
     * Statistics from the most recent cleanup operation.
     */
    data class CleanupStats(
        val eventsDeleted: Long,
        val messagesDeleted: Long,
        val outcomesDeleted: Long,
        val totalRecordsBefore: Long,
        val totalRecordsAfter: Long,
        val durationMs: Long,
    )

    /**
     * Run database cleanup operations asynchronously.
     *
     * This will:
     * 1. Delete events older than retention period
     * 2. Delete messages older than retention period
     * 3. Delete outcomes older than retention period
     * 4. Enforce maximum record counts
     * 5. Run VACUUM if configured
     *
     * @return CleanupStats with information about the cleanup operation
     */
    suspend fun runCleanup(): Result<CleanupStats> = runCatching {
        val startTime = Clock.System.now()
        logger?.logInfo("Starting database cleanup...")

        // Get counts before cleanup
        val eventsBefore = eventRepository.countEvents().getOrElse { 0L }
        val messagesBefore = messageRepository.countMessages().getOrElse { 0L }
        val outcomesBefore = outcomeMemoryRepository.countOutcomes().getOrElse { 0L }
        val totalBefore = eventsBefore + messagesBefore + outcomesBefore

        logger?.logInfo("Current counts - Events: $eventsBefore, Messages: $messagesBefore, Outcomes: $outcomesBefore")

        // Calculate cutoff timestamps
        val now = Clock.System.now()
        val eventCutoff = now - config.eventRetentionDays.days
        val messageCutoff = now - config.messageRetentionDays.days
        val outcomeCutoff = now - config.outcomeRetentionDays.days

        // Clean up events
        eventRepository.deleteEventsOlderThan(eventCutoff).getOrThrow()
        // Also enforce max count
        if (eventsBefore > config.maxEventsToKeep) {
            eventRepository.deleteOldEventsKeepingLast(config.maxEventsToKeep).getOrThrow()
        }

        // Clean up messages
        messageRepository.deleteMessagesOlderThan(messageCutoff).getOrThrow()

        // Clean up outcomes
        outcomeMemoryRepository.deleteOutcomesOlderThan(outcomeCutoff).getOrThrow()
        // Also enforce max count
        if (outcomesBefore > config.maxOutcomesToKeep) {
            outcomeMemoryRepository.deleteOldOutcomesKeepingLast(config.maxOutcomesToKeep).getOrThrow()
        }

        // Get counts after cleanup
        val eventsAfter = eventRepository.countEvents().getOrElse { 0L }
        val messagesAfter = messageRepository.countMessages().getOrElse { 0L }
        val outcomesAfter = outcomeMemoryRepository.countOutcomes().getOrElse { 0L }
        val totalAfter = eventsAfter + messagesAfter + outcomesAfter

        val eventsDeleted = eventsBefore - eventsAfter
        val messagesDeleted = messagesBefore - messagesAfter
        val outcomesDeleted = outcomesBefore - outcomesAfter
        val totalDeleted = totalBefore - totalAfter

        logger?.logInfo("Cleanup completed - Deleted $totalDeleted records (Events: $eventsDeleted, Messages: $messagesDeleted, Outcomes: $outcomesDeleted)")

        // Run VACUUM if configured (this reclaims disk space but can be slow)
        if (config.runVacuum) {
            logger?.logInfo("Running VACUUM to reclaim disk space...")
            // Note: VACUUM is implemented in platform-specific driver extensions
            // For now, we'll skip it in the common implementation
            logger?.logInfo("VACUUM skipped (must be implemented in platform-specific code)")
        }

        val endTime = Clock.System.now()
        val durationMs = (endTime - startTime).inWholeMilliseconds

        logger?.logInfo("Database cleanup finished in ${durationMs}ms")

        CleanupStats(
            eventsDeleted = eventsDeleted,
            messagesDeleted = messagesDeleted,
            outcomesDeleted = outcomesDeleted,
            totalRecordsBefore = totalBefore,
            totalRecordsAfter = totalAfter,
            durationMs = durationMs,
        )
    }

    /**
     * Run cleanup asynchronously in the background.
     * Logs any errors but does not throw.
     */
    fun runCleanupAsync() {
        scope.launch(Dispatchers.IO) {
            runCleanup()
                .onFailure { error ->
                    logger?.logError("Database cleanup failed: ${error.message}")
                }
        }
    }

    /**
     * Get current database statistics without performing cleanup.
     */
    suspend fun getStats(): Result<DatabaseStats> = runCatching {
        val eventCount = eventRepository.countEvents().getOrElse { 0L }
        val messageCount = messageRepository.countMessages().getOrElse { 0L }
        val outcomeCount = outcomeMemoryRepository.countOutcomes().getOrElse { 0L }

        val oldestEvent = eventRepository.getOldestEventTimestamp().getOrNull()
        val oldestMessage = messageRepository.getOldestMessageTimestamp().getOrNull()
        val oldestOutcome = outcomeMemoryRepository.getOldestOutcomeTimestamp().getOrNull()

        DatabaseStats(
            totalEvents = eventCount,
            totalMessages = messageCount,
            totalOutcomes = outcomeCount,
            oldestEventTimestamp = oldestEvent?.let { Instant.fromEpochMilliseconds(it) },
            oldestMessageTimestamp = oldestMessage?.let { Instant.fromEpochMilliseconds(it) },
            oldestOutcomeTimestamp = oldestOutcome?.let { Instant.fromEpochMilliseconds(it) },
        )
    }

    /**
     * Current database statistics.
     */
    data class DatabaseStats(
        val totalEvents: Long,
        val totalMessages: Long,
        val totalOutcomes: Long,
        val oldestEventTimestamp: Instant?,
        val oldestMessageTimestamp: Instant?,
        val oldestOutcomeTimestamp: Instant?,
    ) {
        val totalRecords: Long get() = totalEvents + totalMessages + totalOutcomes
    }
}
