package link.socket.ampere.integrations.issues

import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateResponse
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateError
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest

/**
 * Creates issues in dependency order, resolving references as issues are created.
 *
 * This class handles the complex task of batch issue creation where issues may
 * depend on each other or form parent-child hierarchies. It ensures that:
 *
 * 1. Parents are created before children
 * 2. Dependencies are created before dependents
 * 3. Issue numbers are resolved and injected as issues are created
 * 4. Individual failures don't stop the batch process
 *
 * Example workflow:
 * ```
 * Epic #42 (created first)
 *   ↓
 * Task #43 (created second, references epic)
 *   ↓
 * Task #44 (created third, depends on task #43)
 * ```
 */
class BatchIssueCreator(
    private val provider: IssueTrackerProvider,
) {

    /**
     * Create a batch of issues in dependency order.
     *
     * Issues are sorted topologically so that dependencies and parents
     * are created before the issues that reference them. As each issue
     * is successfully created, its issue number is recorded and made
     * available to subsequent issues that depend on it.
     *
     * @param request The batch creation request with all issues
     * @return Response containing created issues and any errors
     */
    suspend fun createBatch(request: BatchIssueCreateRequest): BatchIssueCreateResponse {
        val created = mutableListOf<CreatedIssue>()
        val errors = mutableListOf<IssueCreateError>()
        val resolved = mutableMapOf<String, Int>() // localId -> issueNumber

        // Topologically sort issues: parents before children, dependencies before dependents
        val sortedIssues = topologicalSort(request.issues)

        // Create issues in sorted order
        for (issue in sortedIssues) {
            val result = provider.createIssue(
                repository = request.repository,
                request = issue,
                resolvedDependencies = resolved,
            )

            result.fold(
                onSuccess = { createdIssue ->
                    created.add(createdIssue)
                    resolved[issue.localId] = createdIssue.issueNumber
                },
                onFailure = { error ->
                    errors.add(
                        IssueCreateError(
                            localId = issue.localId,
                            message = error.message ?: "Unknown error",
                        ),
                    )
                },
            )
        }

        // Set parent relationships (if provider supports formal relationships beyond body text)
        for (issue in request.issues) {
            if (issue.parent != null) {
                val childNumber = resolved[issue.localId] ?: continue
                val parentNumber = resolved[issue.parent] ?: continue

                // Best effort - if this fails, the relationship is still documented in the body
                provider.setParentRelationship(
                    repository = request.repository,
                    childIssueNumber = childNumber,
                    parentIssueNumber = parentNumber,
                )
                // Intentionally ignoring result - relationship already in body
            }
        }

        // Add child summaries to parent issues
        // Find all unique parent issue numbers
        val parentNumbers = created
            .mapNotNull { it.parentIssueNumber }
            .distinct()

        // For each parent, add a summary comment listing all children
        for (parentNumber in parentNumbers) {
            provider.summarizeChildren(
                repository = request.repository,
                parentNumber = parentNumber,
                children = created,
            )
            // Intentionally ignoring result - summaries are a nice-to-have enhancement
        }

        return BatchIssueCreateResponse(
            success = errors.isEmpty(),
            created = created,
            errors = errors,
        )
    }

    /**
     * Sort issues topologically so that:
     * 1. Parents come before children
     * 2. Dependencies come before dependents
     *
     * Uses depth-first search to visit dependencies before the issues that depend on them.
     * This ensures that when we create an issue, all its dependencies and parent have
     * already been created and have issue numbers.
     *
     * Handles cycles gracefully by detecting when we encounter a node already in the
     * current recursion path and skipping it to prevent infinite recursion.
     *
     * Example:
     * ```
     * Input: [task-2 (depends on task-1, parent epic-1), epic-1, task-1 (parent epic-1)]
     * Output: [epic-1, task-1, task-2]
     * ```
     *
     * @param issues Unsorted list of issues to create
     * @return Issues sorted in creation order
     */
    private fun topologicalSort(issues: List<IssueCreateRequest>): List<IssueCreateRequest> {
        val issueMap = issues.associateBy { it.localId }
        val visited = mutableSetOf<String>()
        val inProgress = mutableSetOf<String>() // Track nodes in current recursion path
        val result = mutableListOf<IssueCreateRequest>()

        fun visit(issue: IssueCreateRequest) {
            // Skip if already fully processed
            if (issue.localId in visited) return

            // Cycle detection: skip if currently being processed
            if (issue.localId in inProgress) return

            // Mark as in progress
            inProgress.add(issue.localId)

            // Visit parent first (parents must exist before children)
            issue.parent?.let { parentId ->
                issueMap[parentId]?.let { visit(it) }
            }

            // Visit dependencies first (dependencies must exist before dependents)
            issue.dependsOn.forEach { depId ->
                issueMap[depId]?.let { visit(it) }
            }

            // Mark as visited and add to result
            visited.add(issue.localId)
            inProgress.remove(issue.localId)
            result.add(issue)
        }

        // Visit all issues (handles disconnected components)
        issues.forEach { visit(it) }

        return result
    }
}
