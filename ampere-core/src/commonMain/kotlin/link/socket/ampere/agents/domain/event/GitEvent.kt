package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency

/**
 * GitEvent - Events related to Git operations (branching, committing, pushing, PRs).
 *
 * These events provide observability into Git workflow operations:
 * - BranchCreated: A new Git branch was created
 * - Committed: Changes were committed to the repository
 * - Pushed: Commits were pushed to remote
 * - PullRequestCreated: A pull request was opened on GitHub
 * - OperationFailed: A Git operation failed with an error
 *
 * Events are emitted by the Git tool execution layer (executeGitOperation)
 * to provide fine-grained visibility into the Git workflow.
 *
 * Agents can subscribe to these events to:
 * - Track progress of code changes through the Git workflow
 * - Respond to PR creation (e.g., QA agent starts review)
 * - Handle Git operation failures
 * - Coordinate multi-agent workflows (PM assigns issue → Code writes → QA reviews)
 */
@Serializable
sealed interface GitEvent : Event {

    /**
     * A new Git branch was successfully created.
     *
     * Emitted after `git checkout -b <branchName>` succeeds.
     *
     * @property branchName The name of the created branch
     * @property baseBranch The branch it was created from
     * @property commitSha The commit SHA the branch points to
     * @property issueNumber Optional issue number this branch relates to
     */
    @Serializable
    data class BranchCreated(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val branchName: String,
        val baseBranch: String,
        val commitSha: String,
        val issueNumber: Int? = null,
    ) : GitEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Branch created: $branchName from $baseBranch")
            issueNumber?.let { append(" (issue #$it)") }
            append(" at ${commitSha.take(8)}")
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GitBranchCreated"
        }
    }

    /**
     * Changes were committed to the repository.
     *
     * Emitted after `git commit` succeeds.
     *
     * @property commitSha The short SHA of the created commit
     * @property message The commit message
     * @property filesCommitted List of files included in the commit
     * @property issueNumber Optional issue number referenced in commit message
     */
    @Serializable
    data class Committed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val commitSha: String,
        val message: String,
        val filesCommitted: List<String>,
        val issueNumber: Int? = null,
    ) : GitEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Commit $commitSha: ${message.lines().first()}")
            append(" (${filesCommitted.size} file(s))")
            issueNumber?.let { append(" [#$it]") }
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GitCommitted"
        }
    }

    /**
     * Commits were pushed to the remote repository.
     *
     * Emitted after `git push` succeeds.
     *
     * @property branchName The branch that was pushed
     * @property remoteName The remote repository (usually "origin")
     * @property upstreamSet Whether upstream tracking was configured
     */
    @Serializable
    data class Pushed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val branchName: String,
        val remoteName: String,
        val upstreamSet: Boolean,
    ) : GitEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Pushed $branchName to $remoteName")
            if (upstreamSet) append(" (upstream set)")
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GitPushed"
        }
    }

    /**
     * A pull request was created on GitHub.
     *
     * Emitted after `gh pr create` succeeds.
     *
     * @property prNumber The PR number assigned by GitHub
     * @property url The full URL to view the PR
     * @property title The PR title
     * @property headBranch The source branch with changes
     * @property baseBranch The target branch for merging
     * @property issueNumber Optional issue number this PR closes
     * @property reviewers List of requested reviewers
     * @property draft Whether the PR is a draft
     */
    @Serializable
    data class PullRequestCreated(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val prNumber: Int,
        val url: String,
        val title: String,
        val headBranch: String,
        val baseBranch: String,
        val issueNumber: Int? = null,
        val reviewers: List<String> = emptyList(),
        val draft: Boolean = false,
    ) : GitEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            if (draft) append("Draft ")
            append("PR #$prNumber: $title")
            append(" ($headBranch → $baseBranch)")
            issueNumber?.let { append(" closes #$it") }
            if (reviewers.isNotEmpty()) {
                append(" [reviewers: ${reviewers.joinToString(", ")}]")
            }
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GitPullRequestCreated"
        }
    }

    /**
     * Files were staged for commit.
     *
     * Emitted after `git add` succeeds.
     *
     * @property stagedFiles List of files that were staged
     */
    @Serializable
    data class FilesStaged(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val stagedFiles: List<String>,
    ) : GitEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Staged ${stagedFiles.size} file(s) for commit")
            if (stagedFiles.size <= 3) {
                append(": ${stagedFiles.joinToString(", ")}")
            }
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GitFilesStaged"
        }
    }

    /**
     * A Git operation failed with an error.
     *
     * Emitted when any Git operation (branch, commit, push, PR) fails.
     *
     * @property operation The operation that failed (e.g., "createBranch", "commit")
     * @property errorMessage The error message from git/gh CLI
     * @property isRetryable Whether the operation can be retried
     */
    @Serializable
    data class OperationFailed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        val operation: String,
        val errorMessage: String,
        val isRetryable: Boolean,
    ) : GitEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Git $operation failed: $errorMessage")
            if (isRetryable) append(" (retryable)")
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "GitOperationFailed"
        }
    }
}
