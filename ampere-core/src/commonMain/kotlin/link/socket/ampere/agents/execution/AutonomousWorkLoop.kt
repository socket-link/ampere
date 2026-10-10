package link.socket.ampere.agents.execution

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
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
import link.socket.ampere.agents.events.api.TaskLifecycle
import link.socket.ampere.agents.events.api.openTaskLifecycle
import link.socket.ampere.agents.execution.issue.CodeIssueWorkflow
import link.socket.ampere.integrations.issues.ExistingIssue

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

            try {
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

                        workIssue(issue)

                        delay(config.pollingInterval)
                    } catch (e: CancellationException) {
                        // Never back off and retry on cancellation — that would keep a stopped
                        // loop alive. Let it unwind to the `finally` below.
                        throw e
                    } catch (e: Exception) {
                        println("Error in autonomous work loop: ${e.message}")
                        delay(config.backoffInterval)
                    }
                }
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        job?.cancel()
    }

    /**
     * Hand one claimed [issue] to the agent, publishing its task lifecycle around the work.
     *
     * The loop used to publish a bare `TaskCreated` here and nothing else, so every issue it
     * picked up left a `Pending` item in `WorkspaceStateStore` that never moved and
     * `MilestoneTracker` never saw a completion to count (AMPR-404). The `finally` is what
     * makes that true for a [stop] mid-issue and for a throw out of `workOnIssue` as well as
     * for the ordinary paths: [TaskLifecycle] is terminal exactly once, so it is a no-op when
     * the work already reported how it ended.
     */
    private suspend fun workIssue(issue: ExistingIssue) {
        val lifecycle = eventApi?.openTaskLifecycle(
            taskId = "issue-${issue.number}",
            description = "Working on issue #${issue.number}: ${issue.title}",
            assignedTo = agent.id,
            urgency = Urgency.MEDIUM,
            taskType = ISSUE_TASK_TYPE,
        )

        try {
            val result = workflow.workOnIssue(issue, agent)
            issuesProcessedThisHour++

            result
                .onSuccess { summary ->
                    lifecycle?.completed(summary)
                    eventApi?.publishCodeSubmitted(
                        urgency = Urgency.LOW,
                        filePath = "issue-${issue.number}",
                        changeDescription = "Completed issue #${issue.number}: $summary",
                        reviewRequired = true,
                        assignedTo = null,
                        runId = lifecycle?.runId,
                    )
                }
                .onFailure { throwable -> lifecycle?.failed(throwable) }
        } catch (throwable: Throwable) {
            // Report the real reason, then let the loop's own handlers decide what to do with
            // it. Rethrown unchanged, including a CancellationException.
            lifecycle?.failed(throwable)
            throw throwable
        } finally {
            // The backstop for a path that returned without reporting at all. A no-op above.
            lifecycle?.failed("Abandoned while working on issue #${issue.number}")
        }
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

    companion object {
        /**
         * The task type every issue this loop works is counted under.
         *
         * One constant, not one per issue: `MilestoneTracker` reports `FIRST_SUCCESS` the
         * first time it sees a type complete, and "this agent finished its first issue" is the
         * milestone worth having — not "it finished issue #412".
         */
        const val ISSUE_TASK_TYPE: String = "code-issue"
    }
}
