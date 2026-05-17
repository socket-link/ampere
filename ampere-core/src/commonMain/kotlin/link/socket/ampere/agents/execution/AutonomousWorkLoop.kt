package link.socket.ampere.agents.execution

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.issue.CodeIssueWorkflow

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
 * Manages the autonomous issue-processing loop for a code agent.
 *
 * Continuously polls a [CodeIssueWorkflow] for available issues, claims
 * them with optimistic locking, and hands the work to the supplied agent
 * via the workflow's `workOnIssue` path.
 *
 * Agent-agnostic — accepts any [AutonomousAgent] (typically a
 * `SparkBasedAgent<CodeState>` built by `SparkBasedAgent.Code(...)`).
 *
 * Features:
 * - **Exponential Backoff**: When no work is available, polling slows down exponentially
 * - **Rate Limiting**: Prevents runaway execution by limiting issues per hour
 * - **Graceful Shutdown**: Clean cancellation via stop()
 * - **Error Recovery**: Continues operation even if individual issues fail
 */
class AutonomousWorkLoop<S : AgentState>(
    private val agent: AutonomousAgent<S>,
    private val workflow: CodeIssueWorkflow,
    private val config: WorkLoopConfig = WorkLoopConfig(),
    private val scope: CoroutineScope,
    private val eventApiFactory: ((AgentId) -> AgentEventApi)? = null,
) {
    private val eventApi: AgentEventApi? by lazy {
        eventApiFactory?.invoke(agent.id)
    }
    private val _isRunning = MutableStateFlow(false)

    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var job: Job? = null
    private var issuesProcessedThisHour = 0
    private var hourStartTime = Clock.System.now().toEpochMilliseconds()

    fun start() {
        if (_isRunning.value) return

        job = scope.launch {
            _isRunning.value = true
            var consecutiveNoWork = 0

            while (_isRunning.value) {
                try {
                    if (shouldThrottleForRateLimit()) {
                        delay(config.backoffInterval)
                        continue
                    }

                    val issues = workflow.queryAvailableIssues()
                    if (issues.isEmpty()) {
                        consecutiveNoWork++
                        delay(calculateBackoff(consecutiveNoWork))
                        continue
                    }
                    consecutiveNoWork = 0

                    val issue = issues.first()
                    val claimed = workflow.claimIssue(issue.number)
                    if (claimed.isFailure) {
                        delay(config.pollingInterval)
                        continue
                    }

                    eventApi?.publishTaskCreated(
                        taskId = "issue-${issue.number}",
                        urgency = Urgency.MEDIUM,
                        description = "Working on issue #${issue.number}: ${issue.title}",
                        assignedTo = agent.id,
                    )

                    val result = workflow.workOnIssue(issue, agent)
                    issuesProcessedThisHour++

                    if (result.isSuccess) {
                        eventApi?.publishCodeSubmitted(
                            urgency = Urgency.LOW,
                            filePath = "issue-${issue.number}",
                            changeDescription = "Completed issue #${issue.number}: ${result.getOrNull()}",
                            reviewRequired = true,
                            assignedTo = null,
                        )
                    }

                    delay(config.pollingInterval)
                } catch (e: Exception) {
                    println("Error in autonomous work loop: ${e.message}")
                    delay(config.backoffInterval)
                }
            }

            _isRunning.value = false
        }
    }

    fun stop() {
        _isRunning.value = false
        job?.cancel()
    }

    private fun calculateBackoff(consecutiveNoWork: Int): Duration {
        val seconds = minOf(
            30 * 2.0.pow(consecutiveNoWork.toDouble()).toLong(),
            300,
        )
        return seconds.seconds
    }

    private fun shouldThrottleForRateLimit(): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val hourElapsed = (now - hourStartTime) > 3600_000

        if (hourElapsed) {
            hourStartTime = now
            issuesProcessedThisHour = 0
            return false
        }

        return issuesProcessedThisHour >= config.maxIssuesPerHour
    }
}
