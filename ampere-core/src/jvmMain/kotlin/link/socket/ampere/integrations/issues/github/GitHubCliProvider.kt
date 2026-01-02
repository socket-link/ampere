package link.socket.ampere.integrations.issues.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest
import link.socket.ampere.integrations.issues.ExistingIssue
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.integrations.issues.IssueUpdate

/**
 * GitHub issue tracker provider that uses the gh CLI.
 *
 * The gh CLI provides a robust, well-tested interface to GitHub's API
 * with built-in authentication handling. This implementation wraps gh
 * commands rather than using the REST API directly.
 *
 * Prerequisites:
 * - gh CLI must be installed and available on PATH
 * - User must be authenticated via `gh auth login`
 *
 * Future optimization: Could migrate to direct REST API calls via ktor-client
 * for better performance and more control over rate limiting.
 */
class GitHubCliProvider : IssueTrackerProvider {

    override val providerId = "github-cli"
    override val displayName = "GitHub (via gh CLI)"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    override suspend fun validateConnection(): Result<Unit> = runCatching {
        val result = executeGh("auth", "status")
        if (result.exitCode != 0) {
            error("GitHub CLI not authenticated: ${result.stderr}")
        }
    }

    override suspend fun createIssue(
        repository: String,
        request: IssueCreateRequest,
        resolvedDependencies: Map<String, Int>,
    ): Result<CreatedIssue> = runCatching {
        // Build body with dependency references and parent relationship
        val bodyWithDeps = buildString {
            append(request.body)

            // Add dependency references
            if (request.dependsOn.isNotEmpty()) {
                append("\n\n---\n**Dependencies:**\n")
                request.dependsOn.forEach { depId ->
                    val depNumber = resolvedDependencies[depId]
                    if (depNumber != null) {
                        append("- Depends on #$depNumber\n")
                    }
                }
            }

            // Add parent reference
            if (request.parent != null) {
                val parentNumber = resolvedDependencies[request.parent]
                if (parentNumber != null) {
                    append("\n**Part of:** #$parentNumber\n")
                }
            }
        }

        // Build gh issue create command
        val args = mutableListOf(
            "issue",
            "create",
            "--repo",
            repository,
            "--title",
            request.title,
            "--body",
            bodyWithDeps,
        )

        // Add labels
        request.labels.forEach { label ->
            args.add("--label")
            args.add(label)
        }

        // Add assignees
        request.assignees.forEach { assignee ->
            args.add("--assignee")
            args.add(assignee)
        }

        val result = executeGh(*args.toTypedArray())

        if (result.exitCode != 0) {
            error("Failed to create issue: ${result.stderr}")
        }

        // Parse issue URL from output to extract number
        // gh returns the issue URL like: https://github.com/owner/repo/issues/123
        val url = result.stdout.trim()
        val issueNumber = url.substringAfterLast("/").toIntOrNull()
            ?: error("Could not parse issue number from URL: $url")

        CreatedIssue(
            localId = request.localId,
            issueNumber = issueNumber,
            url = url,
            parentIssueNumber = request.parent?.let { resolvedDependencies[it] },
        )
    }

    override suspend fun setParentRelationship(
        repository: String,
        childIssueNumber: Int,
        parentIssueNumber: Int,
    ): Result<Unit> = runCatching {
        // GitHub's official sub-issues feature is in beta and requires project boards.
        // For now, we document the relationship in the issue body (already done in createIssue).
        //
        // Future enhancement: Use GitHub Projects API to establish formal relationships
        // via: gh api repos/{owner}/{repo}/issues/{number}/sub_issues

        // The relationship is already documented in the issue body from createIssue,
        // so this is a no-op for the gh CLI implementation.
        Unit
    }

    /**
     * Add a summary comment to a parent issue listing all its children.
     *
     * This creates a comment on the parent issue with checkboxes for each child,
     * making it easy to track progress on the epic/parent issue.
     *
     * Example comment:
     * ```markdown
     * ## Subtasks
     *
     * - [ ] #43
     * - [ ] #44
     * - [ ] #45
     * ```
     *
     * @param repository Repository identifier in "owner/repo" format
     * @param parentNumber Issue number of the parent
     * @param children List of all created issues that are children of this parent
     * @return Success if comment was added, Failure otherwise
     */
    override suspend fun summarizeChildren(
        repository: String,
        parentNumber: Int,
        children: List<CreatedIssue>,
    ): Result<Unit> = runCatching {
        // Filter to only children of this parent
        val childIssues = children.filter { it.parentIssueNumber == parentNumber }

        if (childIssues.isEmpty()) {
            return Result.success(Unit)
        }

        // Build checkbox list of children
        val childList = childIssues
            .sortedBy { it.issueNumber }
            .joinToString("\n") { "- [ ] #${it.issueNumber}" }

        val comment = """
            ## Subtasks

            $childList
        """.trimIndent()

        val result = executeGh(
            "issue",
            "comment",
            "--repo",
            repository,
            parentNumber.toString(),
            "--body",
            comment,
        )

        if (result.exitCode != 0) {
            error("Failed to add child summary comment: ${result.stderr}")
        }
    }

    override suspend fun queryIssues(
        repository: String,
        query: IssueQuery,
    ): Result<List<ExistingIssue>> = runCatching {
        val args = mutableListOf(
            "issue",
            "list",
            "--repo",
            repository,
            "--json",
            "number,title,body,state,labels,url",
            "--limit",
            query.limit.toString(),
        )

        // Add state filter
        query.state?.let { state ->
            args.add("--state")
            args.add(
                when (state) {
                    IssueState.Open -> "open"
                    IssueState.Closed -> "closed"
                    IssueState.All -> "all"
                },
            )
        }

        // Add label filters
        query.labels.forEach { label ->
            args.add("--label")
            args.add(label)
        }

        // Add assignee filter
        query.assignee?.let { assignee ->
            args.add("--assignee")
            args.add(assignee)
        }

        // Add search filter for title
        query.titleContains?.let { search ->
            args.add("--search")
            args.add("\"$search\" in:title")
        }

        val result = executeGh(*args.toTypedArray())

        if (result.exitCode != 0) {
            error("Failed to query issues: ${result.stderr}")
        }

        // Parse JSON output
        if (result.stdout.isBlank() || result.stdout.trim() == "[]") {
            emptyList()
        } else {
            json.decodeFromString<List<GitHubIssueJson>>(result.stdout)
                .map { it.toExistingIssue() }
        }
    }

    override suspend fun updateIssue(
        repository: String,
        issueNumber: Int,
        update: IssueUpdate,
    ): Result<ExistingIssue> = runCatching {
        // Build edit command for title and body
        if (update.title != null || update.body != null) {
            val args = mutableListOf(
                "issue",
                "edit",
                "--repo",
                repository,
                issueNumber.toString(),
            )

            update.title?.let { args.addAll(listOf("--title", it)) }
            update.body?.let { args.addAll(listOf("--body", it)) }

            val result = executeGh(*args.toTypedArray())
            if (result.exitCode != 0) {
                error("Failed to update issue: ${result.stderr}")
            }
        }

        // State changes use separate command
        update.state?.let { state ->
            val stateCmd = if (state == IssueState.Closed) "close" else "reopen"
            val result = executeGh(
                "issue",
                stateCmd,
                "--repo",
                repository,
                issueNumber.toString(),
            )
            if (result.exitCode != 0) {
                error("Failed to change issue state: ${result.stderr}")
            }
        }

        // Update labels using GitHub API
        update.labels?.let { newLabels ->
            // Use gh api to atomically replace labels via PUT /repos/{owner}/{repo}/issues/{number}/labels
            // The gh CLI accepts array values via repeated -f labels[]=value flags
            val args = mutableListOf(
                "api",
                "repos/$repository/issues/$issueNumber/labels",
                "-X", "PUT",
            )

            // Add each label as an array element
            // gh api expects: -f labels[]=bug -f labels[]=critical
            newLabels.forEach { label ->
                args.add("-f")
                args.add("labels[]=$label")
            }

            // If newLabels is empty, we still need to make the request to clear all labels
            if (newLabels.isEmpty()) {
                // For empty array, send empty JSON body via field
                args.add("-f")
                args.add("labels=[]")
            }

            val result = executeGh(*args.toTypedArray())
            if (result.exitCode != 0) {
                error("Failed to update labels: ${result.stderr}")
            }
        }

        // TODO: Implement assignee updates
        // Assignees can be updated similarly via:
        // POST /repos/{owner}/{repo}/issues/{number}/assignees

        // Fetch and return the updated issue
        val fetchResult = executeGh(
            "issue",
            "view",
            "--repo",
            repository,
            issueNumber.toString(),
            "--json",
            "number,title,body,state,labels,url",
        )

        if (fetchResult.exitCode != 0) {
            error("Failed to fetch updated issue: ${fetchResult.stderr}")
        }

        json.decodeFromString<GitHubIssueJson>(fetchResult.stdout)
            .toExistingIssue()
    }

    /**
     * Execute a gh CLI command and return the result.
     *
     * @param args Command arguments to pass to gh
     * @return Command result with exit code, stdout, and stderr
     */
    private suspend fun executeGh(vararg args: String): CommandResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("gh", *args)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            CommandResult(exitCode, stdout, stderr)
        }

    /**
     * Result of executing a command.
     */
    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

/**
 * JSON representation of a GitHub issue from gh CLI output.
 */
@Serializable
internal data class GitHubIssueJson(
    val number: Int,
    val title: String,
    val body: String,
    val state: String,
    val labels: List<GitHubLabelJson>,
    val url: String,
) {
    fun toExistingIssue() = ExistingIssue(
        number = number,
        title = title,
        body = body,
        state = when (state.uppercase()) {
            "OPEN" -> IssueState.Open
            "CLOSED" -> IssueState.Closed
            else -> IssueState.Open
        },
        labels = labels.map { it.name },
        url = url,
        parentNumber = null, // Would need to parse from body or use Projects API
    )
}

/**
 * JSON representation of a GitHub label from gh CLI output.
 */
@Serializable
internal data class GitHubLabelJson(val name: String)
