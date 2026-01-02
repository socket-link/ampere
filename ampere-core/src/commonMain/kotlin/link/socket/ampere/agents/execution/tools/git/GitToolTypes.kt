package link.socket.ampere.agents.execution.tools.git

import kotlinx.serialization.Serializable

/**
 * Request to create a new Git branch.
 *
 * @property baseBranch The branch to create the new branch from (default: "main")
 * @property branchName The name of the new branch to create
 * @property issueNumber Optional issue number to include in branch name convention
 */
@Serializable
data class BranchCreateRequest(
    val baseBranch: String = "main",
    val branchName: String,
    val issueNumber: Int? = null,
)

/**
 * Request to commit staged changes.
 *
 * @property message The commit message
 * @property files Specific files to stage before committing (empty = all staged files)
 * @property issueNumber Optional issue number to include in commit message footer
 */
@Serializable
data class CommitRequest(
    val message: String,
    val files: List<String> = emptyList(),
    val issueNumber: Int? = null,
)

/**
 * Request to push a branch to remote.
 *
 * @property branchName The branch to push
 * @property setUpstream Whether to set up tracking (default: true for new branches)
 * @property force Whether to force push (should be false except for specific recovery scenarios)
 */
@Serializable
data class PushRequest(
    val branchName: String,
    val setUpstream: Boolean = true,
    val force: Boolean = false,
)

/**
 * Request to create a pull request.
 *
 * @property title The PR title
 * @property body The PR description/body in markdown
 * @property baseBranch The target branch to merge into (default: "main")
 * @property headBranch The source branch with changes
 * @property issueNumber Optional issue number to auto-link (adds "Closes #N" to body)
 * @property reviewers List of GitHub usernames to request review from
 * @property labels Labels to apply to the PR
 * @property draft Whether to create as draft PR
 */
@Serializable
data class PullRequestCreateRequest(
    val title: String,
    val body: String,
    val baseBranch: String = "main",
    val headBranch: String,
    val issueNumber: Int? = null,
    val reviewers: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val draft: Boolean = false,
)

/**
 * Result of successfully creating a pull request.
 *
 * @property number The PR number assigned by GitHub
 * @property url Full URL to view the created PR
 * @property headBranch The source branch
 * @property baseBranch The target branch
 */
@Serializable
data class CreatedPullRequest(
    val number: Int,
    val url: String,
    val headBranch: String,
    val baseBranch: String,
)

/**
 * Result of checking Git status.
 *
 * @property branch Current branch name
 * @property ahead Number of commits ahead of upstream
 * @property behind Number of commits behind upstream
 * @property staged List of staged file paths
 * @property modified List of modified but unstaged file paths
 * @property untracked List of untracked file paths
 * @property hasConflicts Whether there are merge conflicts
 */
@Serializable
data class GitStatusResult(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: List<String>,
    val modified: List<String>,
    val untracked: List<String>,
    val hasConflicts: Boolean,
)

/**
 * Result of creating a branch.
 *
 * @property branchName The name of the created branch
 * @property baseBranch The branch it was created from
 * @property commitSha The commit SHA the branch points to
 */
@Serializable
data class CreatedBranch(
    val branchName: String,
    val baseBranch: String,
    val commitSha: String,
)

/**
 * Result of creating a commit.
 *
 * @property commitSha The SHA of the created commit
 * @property message The commit message used
 * @property filesCommitted List of files included in the commit
 */
@Serializable
data class CreatedCommit(
    val commitSha: String,
    val message: String,
    val filesCommitted: List<String>,
)

/**
 * Result of pushing to remote.
 *
 * @property branchName The branch that was pushed
 * @property remoteName The remote that was pushed to (usually "origin")
 * @property upstreamSet Whether upstream tracking was configured
 */
@Serializable
data class PushResult(
    val branchName: String,
    val remoteName: String = "origin",
    val upstreamSet: Boolean,
)

/**
 * Result of staging files.
 *
 * @property stagedFiles List of files that were staged
 */
@Serializable
data class StagedFilesResult(
    val stagedFiles: List<String>,
)

/**
 * Request to checkout a branch.
 *
 * @property branchName The branch to checkout
 * @property createIfNotExists Whether to create the branch if it doesn't exist
 */
@Serializable
data class CheckoutRequest(
    val branchName: String,
    val createIfNotExists: Boolean = false,
)

/**
 * Aggregated request for Git operations, used as the context for Git tools.
 *
 * Only one of the operation-specific fields should be non-null, indicating
 * which operation to perform.
 *
 * @property repository Repository path (local filesystem path)
 * @property createBranch Request to create a branch
 * @property commit Request to commit changes
 * @property push Request to push to remote
 * @property createPullRequest Request to create a PR
 * @property checkout Request to checkout a branch
 * @property stageFiles Files to stage (empty list = stage all)
 * @property getStatus Whether to get git status
 */
@Serializable
data class GitOperationRequest(
    val repository: String,
    val createBranch: BranchCreateRequest? = null,
    val commit: CommitRequest? = null,
    val push: PushRequest? = null,
    val createPullRequest: PullRequestCreateRequest? = null,
    val checkout: CheckoutRequest? = null,
    val stageFiles: List<String>? = null,
    val getStatus: Boolean = false,
)

/**
 * Response from a Git operation.
 *
 * @property success Whether the operation succeeded
 * @property createdBranch Result if a branch was created
 * @property createdCommit Result if a commit was created
 * @property pushResult Result if a push was performed
 * @property createdPullRequest Result if a PR was created
 * @property statusResult Result if status was requested
 * @property stagedFiles Result if files were staged
 * @property error Error message if operation failed
 */
@Serializable
data class GitOperationResponse(
    val success: Boolean,
    val createdBranch: CreatedBranch? = null,
    val createdCommit: CreatedCommit? = null,
    val pushResult: PushResult? = null,
    val createdPullRequest: CreatedPullRequest? = null,
    val statusResult: GitStatusResult? = null,
    val stagedFiles: StagedFilesResult? = null,
    val error: String? = null,
)
