package link.socket.ampere.integrations.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.execution.tools.git.BranchCreateRequest
import link.socket.ampere.agents.execution.tools.git.CommitRequest
import link.socket.ampere.agents.execution.tools.git.CreatedBranch
import link.socket.ampere.agents.execution.tools.git.CreatedCommit
import link.socket.ampere.agents.execution.tools.git.CreatedPullRequest
import link.socket.ampere.agents.execution.tools.git.GitStatusResult
import link.socket.ampere.agents.execution.tools.git.PullRequestCreateRequest
import link.socket.ampere.agents.execution.tools.git.PushRequest
import java.io.File

/**
 * Git repository provider that uses the git and gh CLI tools.
 *
 * The git CLI provides direct access to Git operations (branching, committing, pushing),
 * while gh CLI handles GitHub-specific operations like pull request creation.
 *
 * Prerequisites:
 * - git CLI must be installed and available on PATH
 * - gh CLI must be installed and available on PATH
 * - Repository must be a valid Git repository
 * - User must be authenticated via `gh auth login` for PR operations
 *
 * @property workingDirectory The repository root directory (defaults to current directory)
 */
class GitCliProvider(
    private val workingDirectory: File = File("."),
) {

    /**
     * Validates that the working directory is a valid Git repository.
     *
     * @return Success if valid repository, Failure with error message otherwise
     */
    suspend fun validateRepository(): Result<Unit> = runCatching {
        val result = executeGit("status", "--porcelain")
        if (result.exitCode == 128) {
            error("Not a git repository: ${workingDirectory.absolutePath}")
        }
    }

    /**
     * Gets the name of the current branch.
     *
     * @return Current branch name
     */
    suspend fun getCurrentBranch(): Result<String> = runCatching {
        val result = executeGit("branch", "--show-current")
        if (result.exitCode != 0) {
            error("Failed to get current branch: ${result.stderr}")
        }
        result.stdout.trim()
    }

    /**
     * Creates a new branch from a base branch and checks it out.
     *
     * @param request Branch creation parameters
     * @return Created branch information
     */
    suspend fun createBranch(request: BranchCreateRequest): Result<CreatedBranch> = runCatching {
        // Fetch latest from remote to ensure we have up-to-date refs
        val fetchResult = executeGit("fetch", "origin", request.baseBranch)
        if (fetchResult.exitCode != 0) {
            error("Failed to fetch base branch: ${fetchResult.stderr}")
        }

        // Create and checkout branch from origin/baseBranch
        val checkoutResult = executeGit(
            "checkout",
            "-b",
            request.branchName,
            "origin/${request.baseBranch}",
        )

        if (checkoutResult.exitCode != 0) {
            error("Failed to create branch: ${checkoutResult.stderr}")
        }

        // Get the commit SHA
        val shaResult = executeGit("rev-parse", "HEAD")
        val commitSha = shaResult.stdout.trim()

        CreatedBranch(
            branchName = request.branchName,
            baseBranch = request.baseBranch,
            commitSha = commitSha,
        )
    }

    /**
     * Gets the current repository status including staged/modified/untracked files.
     *
     * @return Repository status information
     */
    suspend fun getStatus(): Result<GitStatusResult> = runCatching {
        // Get current branch
        val branchResult = executeGit("branch", "--show-current")
        if (branchResult.exitCode != 0) {
            error("Failed to get branch: ${branchResult.stderr}")
        }

        // Get porcelain status
        val statusResult = executeGit("status", "--porcelain")
        if (statusResult.exitCode != 0) {
            error("Failed to get status: ${statusResult.stderr}")
        }

        // Get ahead/behind counts (may fail if no upstream)
        val aheadBehindResult = executeGit("rev-list", "--left-right", "--count", "HEAD...@{u}")
        val (ahead, behind) = if (aheadBehindResult.exitCode == 0) {
            aheadBehindResult.stdout.trim().split("\t")
                .map { it.toIntOrNull() ?: 0 }
                .let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
        } else {
            0 to 0 // No upstream branch
        }

        // Parse status output
        val lines = statusResult.stdout.lines().filter { it.isNotBlank() }
        val staged = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        var hasConflicts = false

        lines.forEach { line ->
            when {
                line.startsWith("A ") || line.startsWith("M ") || line.startsWith("D ") -> {
                    // Staged file (first char is the index status)
                    staged.add(line.substring(3))
                }
                line.startsWith(" M") || line.startsWith("MM") || line.startsWith(" D") -> {
                    // Modified but not staged (second char is working tree status)
                    modified.add(line.substring(3))
                }
                line.startsWith("??") -> {
                    // Untracked file
                    untracked.add(line.substring(3))
                }
                line.startsWith("UU") || line.startsWith("AA") || line.startsWith("DD") -> {
                    // Merge conflict
                    hasConflicts = true
                }
            }
        }

        GitStatusResult(
            branch = branchResult.stdout.trim(),
            ahead = ahead,
            behind = behind,
            staged = staged,
            modified = modified,
            untracked = untracked,
            hasConflicts = hasConflicts,
        )
    }

    /**
     * Stages files for commit.
     *
     * @param files List of files to stage (empty = stage all modified/untracked files)
     * @return List of files that were staged
     */
    suspend fun stageFiles(files: List<String>): Result<List<String>> = runCatching {
        val args = if (files.isEmpty()) {
            listOf("add", "-A")
        } else {
            listOf("add") + files
        }

        val result = executeGit(*args.toTypedArray())
        if (result.exitCode != 0) {
            error("Failed to stage files: ${result.stderr}")
        }

        // Return what was staged by querying status
        val status = getStatus().getOrThrow()
        status.staged
    }

    /**
     * Commits staged changes with a message.
     *
     * @param request Commit parameters including message and optional issue reference
     * @return Commit information
     */
    suspend fun commit(request: CommitRequest): Result<CreatedCommit> = runCatching {
        // Build commit message with optional issue reference
        val message = buildString {
            append(request.message)
            if (request.issueNumber != null) {
                append("\n\nRefs #${request.issueNumber}")
            }
        }

        // Stage specific files if provided
        if (request.files.isNotEmpty()) {
            stageFiles(request.files).getOrThrow()
        }

        val result = executeGit("commit", "-m", message)

        if (result.exitCode != 0) {
            error("Failed to commit: ${result.stderr}")
        }

        // Get commit SHA
        val shaResult = executeGit("rev-parse", "HEAD")
        val commitSha = shaResult.stdout.trim().take(8)

        // Get the files included in the commit
        val filesResult = executeGit("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        val filesCommitted = filesResult.stdout.lines().filter { it.isNotBlank() }

        CreatedCommit(
            commitSha = commitSha,
            message = message,
            filesCommitted = filesCommitted,
        )
    }

    /**
     * Pushes commits to remote repository.
     *
     * @param request Push parameters including branch name and upstream settings
     * @return Success if pushed successfully
     */
    suspend fun push(request: PushRequest): Result<Unit> = runCatching {
        val args = mutableListOf("push")

        if (request.setUpstream) {
            args.addAll(listOf("-u", "origin", request.branchName))
        } else {
            args.add("origin")
            args.add(request.branchName)
        }

        if (request.force) {
            args.add("--force-with-lease")
        }

        val result = executeGit(*args.toTypedArray())

        if (result.exitCode != 0) {
            error("Failed to push: ${result.stderr}")
        }
    }

    /**
     * Creates a pull request on GitHub.
     *
     * @param request PR creation parameters
     * @return Created pull request information
     */
    suspend fun createPullRequest(request: PullRequestCreateRequest): Result<CreatedPullRequest> = runCatching {
        val args = mutableListOf(
            "pr",
            "create",
            "--title",
            request.title,
            "--body",
            buildPRBody(request),
            "--base",
            request.baseBranch,
            "--head",
            request.headBranch,
        )

        if (request.draft) {
            args.add("--draft")
        }

        request.reviewers.forEach {
            args.addAll(listOf("--reviewer", it))
        }

        request.labels.forEach {
            args.addAll(listOf("--label", it))
        }

        val result = executeGh(*args.toTypedArray())

        if (result.exitCode != 0) {
            error("Failed to create PR: ${result.stderr}")
        }

        // Parse PR URL to get number
        // gh returns the PR URL like: https://github.com/owner/repo/pull/123
        val url = result.stdout.trim()
        val prNumber = url.substringAfterLast("/").toIntOrNull()
            ?: error("Could not parse PR number from URL: $url")

        CreatedPullRequest(
            number = prNumber,
            url = url,
            headBranch = request.headBranch,
            baseBranch = request.baseBranch,
        )
    }

    /**
     * Builds the PR body with optional issue reference.
     */
    private fun buildPRBody(request: PullRequestCreateRequest): String = buildString {
        append(request.body)
        if (request.issueNumber != null) {
            append("\n\n---\nCloses #${request.issueNumber}")
        }
    }

    /**
     * Execute a git command and return the result.
     *
     * @param args Command arguments to pass to git
     * @return Command result with exit code, stdout, and stderr
     */
    private suspend fun executeGit(vararg args: String): CommandResult = execute("git", *args)

    /**
     * Execute a gh command and return the result.
     *
     * @param args Command arguments to pass to gh
     * @return Command result with exit code, stdout, and stderr
     */
    private suspend fun executeGh(vararg args: String): CommandResult = execute("gh", *args)

    /**
     * Execute a command and return the result.
     *
     * @param command The command to execute (e.g., "git", "gh")
     * @param args Command arguments
     * @return Command result with exit code, stdout, and stderr
     */
    private suspend fun execute(command: String, vararg args: String): CommandResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command, *args)
                .directory(workingDirectory)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            CommandResult(exitCode, stdout, stderr)
        }

    /**
     * Result of executing a command.
     *
     * @property exitCode The process exit code (0 = success)
     * @property stdout Standard output from the command
     * @property stderr Standard error from the command
     */
    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
