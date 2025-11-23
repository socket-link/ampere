package link.socket.ampere.agents.events.meetings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/**
 * Service that periodically checks for scheduled meetings that should start
 * and triggers the start sequence via MeetingOrchestrator.
 *
 * This scheduler runs a coroutine that wakes up at configured intervals,
 * queries for meetings past their scheduled time, and starts them automatically.
 */
class MeetingScheduler(
    private val repository: MeetingRepository,
    private val orchestrator: MeetingOrchestrator,
    private val coroutineScope: CoroutineScope,
    private val checkInterval: Duration = 30.seconds,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    private var schedulerJob: Job? = null

    /**
     * Start the scheduler coroutine that periodically checks for meetings to start.
     * If the scheduler is already running, this call is ignored.
     */
    fun start() {
        if (schedulerJob?.isActive == true) {
            return
        }

        schedulerJob = coroutineScope.launch {
            while (isActive) {
                try {
                    checkAndStartMeetings()
                } catch (e: Exception) {
                    logger.logError(
                        message = "Error during scheduled meeting check",
                        throwable = e,
                    )
                }

                delay(checkInterval)
            }
        }
    }

    /**
     * Stop the scheduler coroutine.
     * If the scheduler is not running, this call is ignored.
     */
    fun stop() {
        val job = schedulerJob
        if (job == null || !job.isActive) {
            return
        }

        job.cancel()
        schedulerJob = null
    }

    /**
     * Check if the scheduler is currently running.
     */
    fun isRunning(): Boolean =
        schedulerJob?.isActive == true

    /**
     * Manually check for and start any scheduled meetings that are past their start time.
     * This is useful for testing or for triggering an immediate check outside the regular interval.
     *
     * @return The number of meetings that were started
     */
    suspend fun checkAndStartMeetings(): Int {
        val now = Clock.System.now()

        val scheduledMeetingsResult = repository.getScheduledMeetings(before = now)
        if (scheduledMeetingsResult.isFailure) {
            val exception = scheduledMeetingsResult.exceptionOrNull()
            logger.logError(
                message = "Failed to retrieve scheduled meetings",
                throwable = exception,
            )
            return 0
        }

        val scheduledMeetings = scheduledMeetingsResult.getOrNull() ?: emptyList()

        if (scheduledMeetings.isEmpty()) {
            return 0
        }

        var startedCount = 0
        for (meeting in scheduledMeetings) {
            try {
                val startResult = orchestrator.startMeeting(meeting.id)
                if (startResult.isSuccess) {
                    startedCount++
                } else {
                    val exception = startResult.exceptionOrNull()
                    logger.logError(
                        message = "Failed to start meeting ${meeting.id}",
                        throwable = exception,
                    )
                }
            } catch (e: Exception) {
                logger.logError(
                    message = "Exception while starting meeting ${meeting.id}",
                    throwable = e,
                )
            }
        }

        return startedCount
    }
}
