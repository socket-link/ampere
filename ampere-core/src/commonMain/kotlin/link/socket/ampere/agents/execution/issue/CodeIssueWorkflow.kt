package link.socket.ampere.agents.execution.issue

import kotlinx.coroutines.delay
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.definition.code.IssueWorkflowStatus
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.integrations.issues.ExistingIssue
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.integrations.issues.IssueUpdate

/**
 * Issue → task → PR workflow extracted from the legacy `CodeAgent`.
 *
 * Encapsulates the integration layer between an [IssueTrackerProvider] and an
 * autonomous agent: discovers issues, claims them with optimistic locking,
 * hands the work to the agent as a [Task.CodeChange], and reflects progress
 * back as label updates on the issue.
 *
 * Agent-agnostic — works with any [AutonomousAgent]<*> the surrounding loop
 * dispatches the task to (typically a `SparkBasedAgent<CodeState>` produced
 * by `SparkBasedAgent.Code(...)`).
 *
 * @param issueTrackerProvider Source of issues (typically a GitHub provider).
 * @param repository Repository identifier the provider scopes its queries to.
 * @param agentId Identifier of the agent doing the work — used in claim
 *   comments and (eventually) in the claim-by-assignee lookup.
 */
class CodeIssueWorkflow(
    private val issueTrackerProvider: IssueTrackerProvider,
    private val repository: String,
    private val agentId: AgentId,
) {

    /** Open issues matching this workflow's intake criteria. */
    suspend fun queryAvailableIssues(): List<ExistingIssue> =
        issueTrackerProvider.queryIssues(
            repository = repository,
            query = IssueQuery(
                state = IssueState.Open,
                assignee = null,
                labels = listOf("code", "task"),
                limit = 10,
            ),
        ).getOrElse { emptyList() }

    /** Open issues already assigned to this workflow's agent. */
    suspend fun queryAssignedIssues(): List<ExistingIssue> =
        issueTrackerProvider.queryIssues(
            repository = repository,
            query = IssueQuery(
                state = IssueState.Open,
                assignee = "CodeWriterAgent",
                labels = emptyList(),
                limit = 20,
            ),
        ).getOrElse { emptyList() }

    /**
     * Attempt to claim an unassigned issue using optimistic locking.
     *
     * Reads the current state → checks for prior workflow labels → writes the
     * CLAIMED transition → re-reads to detect lost races against another
     * agent that wrote concurrently.
     */
    suspend fun claimIssue(issueNumber: Int): Result<Unit> {
        try {
            val currentIssue = issueTrackerProvider.queryIssues(
                repository = repository,
                query = IssueQuery(state = IssueState.Open, limit = 100),
            ).getOrNull()?.find { it.number == issueNumber }
                ?: return Result.failure(
                    IllegalArgumentException("Issue #$issueNumber not found"),
                )

            val currentStatus = IssueWorkflowStatus.fromLabels(currentIssue.labels)
            if (currentStatus != null) {
                return Result.failure(
                    IllegalStateException("Issue already in ${currentStatus.name} status"),
                )
            }

            val updateResult = updateIssueStatus(
                issueNumber = issueNumber,
                status = IssueWorkflowStatus.CLAIMED,
                comment = "$agentId claiming this issue",
            )
            if (updateResult.isFailure) {
                return Result.failure(
                    updateResult.exceptionOrNull() ?: Exception("Failed to claim issue"),
                )
            }

            // Let the provider propagate the update before verifying.
            delay(500)

            val verifiedIssue = issueTrackerProvider.queryIssues(
                repository = repository,
                query = IssueQuery(state = IssueState.Open, limit = 100),
            ).getOrNull()?.find { it.number == issueNumber }
            val finalStatus = IssueWorkflowStatus.fromLabels(verifiedIssue?.labels ?: emptyList())

            return if (finalStatus != IssueWorkflowStatus.CLAIMED) {
                Result.failure(
                    IllegalStateException("Race condition: another agent claimed the issue"),
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Execute a claimed issue end-to-end: mark IN_PROGRESS → hand the issue
     * to [agent] as a `Task.CodeChange` → reflect the outcome back as
     * IN_REVIEW or BLOCKED labels.
     *
     * The agent receives the full issue body as the task description; it is
     * responsible for planning and executing the code/git work via its
     * standard `runTask` path.
     */
    suspend fun <S : AgentState> workOnIssue(
        issue: ExistingIssue,
        agent: AutonomousAgent<S>,
    ): Result<String> {
        try {
            updateIssueStatusSafely(
                issueNumber = issue.number,
                status = IssueWorkflowStatus.IN_PROGRESS,
                comment = "Starting implementation",
            )

            val task = Task.CodeChange(
                id = "issue-${issue.number}",
                status = TaskStatus.Pending,
                description = buildString {
                    appendLine("# ${issue.title}")
                    appendLine()
                    appendLine(issue.body)
                    appendLine()
                    appendLine("Issue: ${issue.url}")
                    appendLine()
                    appendLine("**Requirements:**")
                    appendLine("- Implement the feature/fix described above")
                    appendLine("- Create a feature branch")
                    appendLine("- Write tests if applicable")
                    appendLine("- Commit with conventional commit message")
                    appendLine("- Push to remote")
                    appendLine("- Create PR with 'Closes #${issue.number}'")
                },
            )

            return when (val outcome = agent.runTask(task)) {
                is Outcome.Success -> Result.success(
                    "Issue #${issue.number} completed successfully. " +
                        "PR created and ready for review.",
                )
                is Outcome.Failure -> {
                    updateIssueStatusSafely(
                        issueNumber = issue.number,
                        status = IssueWorkflowStatus.BLOCKED,
                        comment = "Execution failed: ${outcome.id}",
                    )
                    Result.failure(Exception("Execution failed: ${outcome.id}"))
                }
                else -> {
                    updateIssueStatusSafely(
                        issueNumber = issue.number,
                        status = IssueWorkflowStatus.BLOCKED,
                        comment = "Unexpected outcome: ${outcome::class.simpleName}",
                    )
                    Result.failure(Exception("Unexpected outcome: ${outcome::class.simpleName}"))
                }
            }
        } catch (e: Exception) {
            updateIssueStatusSafely(
                issueNumber = issue.number,
                status = IssueWorkflowStatus.BLOCKED,
                comment = "Error: ${e.message}",
            )
            return Result.failure(e)
        }
    }

    /**
     * Replace the issue's workflow labels with the ones implied by [status].
     *
     * Lower-level than [claimIssue] / [workOnIssue]; exposed for callers that
     * need to drive the state machine directly (e.g. after a PR merge fires
     * an external transition).
     */
    suspend fun updateIssueStatus(
        issueNumber: Int,
        status: IssueWorkflowStatus,
        comment: String? = null,
    ): Result<ExistingIssue> {
        val currentIssue = issueTrackerProvider.queryIssues(
            repository = repository,
            query = IssueQuery(state = IssueState.Open, limit = 1),
        ).getOrElse { emptyList() }
            .find { it.number == issueNumber }
            ?: return Result.failure(
                IllegalArgumentException("Issue #$issueNumber not found"),
            )

        val newLabels = currentIssue.labels.toMutableSet().apply {
            removeAll(status.removeLabels.toSet())
            addAll(status.addLabels)
        }
        return issueTrackerProvider.updateIssue(
            repository = repository,
            issueNumber = issueNumber,
            update = IssueUpdate(labels = newLabels.toList()),
        )
    }

    private suspend fun updateIssueStatusSafely(
        issueNumber: Int,
        status: IssueWorkflowStatus,
        comment: String,
    ) {
        updateIssueStatus(issueNumber, status, comment)
            .onFailure { error ->
                println(
                    "Warning: Failed to update issue #$issueNumber status to ${status.name}: ${error.message}",
                )
            }
    }
}
