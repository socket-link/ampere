package link.socket.ampere.agents.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.integrations.issues.ExistingIssue
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for autonomous work loop behavior.
 *
 * @property maxConcurrentIssues Maximum number of issues to work on simultaneously
 * @property maxExecutionTimePerIssue Maximum time allowed per issue before timeout
 * @property maxIssuesPerHour Rate limit - maximum issues to process per hour
 * @property pollingInterval How often to poll for new issues when work is available
 * @property backoffInterval Delay after errors or rate limit exceeded
 */
data class WorkLoopConfig(
    val maxConcurrentIssues: Int = 1,
    val maxExecutionTimePerIssue: Duration = 30.minutes,
    val maxIssuesPerHour: Int = 10,
    val pollingInterval: Duration = 30.seconds,
    val backoffInterval: Duration = 5.minutes,
)

/**
 * Manages autonomous work loop for CodeAgent.
 *
 * This class implements the continuous polling strategy for autonomous issue processing:
 * 1. Poll for available issues
 * 2. Claim an issue (with optimistic locking)
 * 3. Execute the full workflow (plan → code → PR)
 * 4. Repeat
 *
 * Features:
 * - **Exponential Backoff**: When no work is available, polling slows down exponentially
 * - **Rate Limiting**: Prevents runaway execution by limiting issues per hour
 * - **Graceful Shutdown**: Clean cancellation via stop()
 * - **Error Recovery**: Continues operation even if individual issues fail
 *
 * ## Usage
 *
 * ```kotlin
 * val loop = AutonomousWorkLoop(
 *     agent = codeAgent,
 *     config = WorkLoopConfig(maxIssuesPerHour = 5),
 *     scope = coroutineScope
 * )
 *
 * loop.start()  // Begin autonomous operation
 * // ...
 * loop.stop()   // Stop gracefully
 * ```
 *
 * ## Backoff Strategy
 *
 * When no issues are available, the polling interval increases:
 * - 0 consecutive: 30s
 * - 1 consecutive: 60s (1 minute)
 * - 2 consecutive: 120s (2 minutes)
 * - 3+ consecutive: 300s (5 minutes, capped)
 *
 * This prevents excessive API calls when the issue queue is empty.
 *
 * ## Rate Limiting
 *
 * To prevent runaway execution or API abuse, the loop enforces a maximum
 * number of issues per hour. Once the limit is reached, the loop backs off
 * for the configured backoff interval.
 *
 * @param agent The CodeAgent instance to execute workflows
 * @param config Configuration for loop behavior
 * @param scope CoroutineScope for launching the polling loop
 */
class AutonomousWorkLoop(
    private val agent: CodeAgent,
    private val config: WorkLoopConfig = WorkLoopConfig(),
    private val scope: CoroutineScope,
) {
    private val _isRunning = MutableStateFlow(false)

    /**
     * Observable state indicating whether the work loop is currently running.
     */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var job: Job? = null
    private var issuesProcessedThisHour = 0
    private var hourStartTime = System.currentTimeMillis()

    /**
     * Start the autonomous work loop.
     *
     * Begins continuous polling for available issues. The loop will:
     * 1. Check rate limits
     * 2. Query for available issues
     * 3. Attempt to claim the first available issue
     * 4. Execute the full workflow on claimed issues
     * 5. Apply backoff when no work is available
     *
     * If the loop is already running, this is a no-op.
     *
     * The loop runs until stop() is called or an unrecoverable error occurs.
     */
    fun start() {
        if (_isRunning.value) return

        job = scope.launch {
            _isRunning.value = true
            var consecutiveNoWork = 0

            while (_isRunning.value) {
                try {
                    // Rate limit check
                    if (shouldThrottleForRateLimit()) {
                        delay(config.backoffInterval)
                        continue
                    }

                    // Discover available issues
                    val issues = agent.queryAvailableIssues()

                    if (issues.isEmpty()) {
                        consecutiveNoWork++
                        val backoff = calculateBackoff(consecutiveNoWork)
                        delay(backoff)
                        continue
                    }

                    consecutiveNoWork = 0

                    // Try to claim and work on first issue
                    val issue = issues.first()
                    val claimed = agent.claimIssue(issue.number)

                    if (claimed.isFailure) {
                        // Another agent claimed it, continue to next
                        delay(config.pollingInterval)
                        continue
                    }

                    // Work on the issue
                    agent.workOnIssue(issue)
                    issuesProcessedThisHour++

                    delay(config.pollingInterval)
                } catch (e: Exception) {
                    // Log error and continue
                    println("Error in autonomous work loop: ${e.message}")
                    delay(config.backoffInterval)
                }
            }

            _isRunning.value = false
        }
    }

    /**
     * Stop the autonomous work loop.
     *
     * Gracefully cancels the polling loop. Any in-progress issue will complete,
     * but no new issues will be claimed.
     *
     * This method is idempotent - calling it multiple times is safe.
     */
    fun stop() {
        _isRunning.value = false
        job?.cancel()
    }

    /**
     * Calculate exponential backoff delay based on consecutive failures to find work.
     *
     * Implements exponential backoff with a cap:
     * - 0 consecutive: 30 seconds
     * - 1 consecutive: 60 seconds (1 minute)
     * - 2 consecutive: 120 seconds (2 minutes)
     * - 3+ consecutive: 300 seconds (5 minutes, capped)
     *
     * @param consecutiveNoWork Number of consecutive polls that found no work
     * @return Delay duration before next poll
     */
    private fun calculateBackoff(consecutiveNoWork: Int): Duration {
        // Exponential backoff: 30s, 1m, 2m, 5m (capped)
        val seconds = minOf(
            30 * 2.0.pow(consecutiveNoWork.toDouble()).toLong(),
            300,
        )
        return seconds.seconds
    }

    /**
     * Check if we should throttle due to rate limiting.
     *
     * Rate limiting is enforced per-hour. Once the configured maximum
     * issues per hour is reached, this returns true until the next hour begins.
     *
     * The hour window resets when:
     * - More than 1 hour has elapsed since hourStartTime
     *
     * @return true if rate limit is exceeded, false otherwise
     */
    private fun shouldThrottleForRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        val hourElapsed = (now - hourStartTime) > 3600_000

        if (hourElapsed) {
            hourStartTime = now
            issuesProcessedThisHour = 0
            return false
        }

        return issuesProcessedThisHour >= config.maxIssuesPerHour
    }
}
