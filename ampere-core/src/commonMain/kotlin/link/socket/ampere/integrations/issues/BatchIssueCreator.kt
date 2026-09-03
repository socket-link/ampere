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
 * 5. A batch whose declared edges form a cycle is refused, not reordered
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
     * If the `parent` / `dependsOn` edges contain a cycle there is no order that
     * honours them, so nothing is created: the response carries a single
     * [IssueCreateError.dependencyCycle] naming the path and `success = false`.
     * Silently dropping the back-edge (the pre-AMPR-322 behaviour) produced a
     * `success = true` batch created in an order that violated its own
     * declared dependencies.
     *
     * @param request The batch creation request with all issues
     * @return Response containing created issues and any errors
     */
    suspend fun createBatch(request: BatchIssueCreateRequest): BatchIssueCreateResponse {
        val created = mutableListOf<CreatedIssue>()
        val errors = mutableListOf<IssueCreateError>()
        val resolved = mutableMapOf<String, Int>() // localId -> issueNumber

        // Topologically sort issues: parents before children, dependencies before dependents
        val sortedIssues = when (val sort = topologicalSort(request.issues)) {
            is SortResult.Sorted -> sort.issues
            is SortResult.Cycle -> return BatchIssueCreateResponse(
                success = false,
                created = emptyList(),
                errors = listOf(IssueCreateError.dependencyCycle(sort.path)),
            )
        }

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

        // Link dependencies using provider-specific APIs (if supported).
        for (issue in request.issues) {
            if (issue.dependsOn.isNotEmpty()) {
                val issueNumber = resolved[issue.localId] ?: continue
                val dependencyNumbers = issue.dependsOn.mapNotNull { resolved[it] }

                if (dependencyNumbers.isNotEmpty()) {
                    // Best effort - dependency references already exist in the body.
                    provider.linkDependencies(
                        repository = request.repository,
                        issueNumber = issueNumber,
                        dependsOnIssueNumbers = dependencyNumbers,
                    )
                }
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

    /** Outcome of [topologicalSort]: an order that honours every edge, or the first cycle that makes one impossible. */
    private sealed interface SortResult {
        data class Sorted(val issues: List<IssueCreateRequest>) : SortResult

        /** [path] is the closed walk, first node repeated last: `a -> b -> a`. */
        data class Cycle(val path: List<String>) : SortResult
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
     * A back-edge (a node already on the current recursion path) means the declared
     * edges are cyclic. Sorting stops and the cycle is returned as a path; it is
     * never "repaired" by dropping the edge. Edges to ids that are not in the batch
     * are ignored here — they cannot be created, and are reported by the provider
     * step, not the sort.
     *
     * Example:
     * ```
     * Input: [task-2 (depends on task-1, parent epic-1), epic-1, task-1 (parent epic-1)]
     * Output: Sorted([epic-1, task-1, task-2])
     * ```
     *
     * @param issues Unsorted list of issues to create
     * @return Issues sorted in creation order, or the first cycle found
     */
    private fun topologicalSort(issues: List<IssueCreateRequest>): SortResult {
        val issueMap = issues.associateBy { it.localId }
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>() // Current recursion path, in order
        val result = mutableListOf<IssueCreateRequest>()

        // Returns the cycle path on a back-edge, null when the subtree sorted cleanly.
        fun visit(issue: IssueCreateRequest): List<String>? {
            // Skip if already fully processed
            if (issue.localId in visited) return null

            // Cycle detection: a node already on the current path is a back-edge
            val backEdge = path.indexOf(issue.localId)
            if (backEdge >= 0) return path.subList(backEdge, path.size) + issue.localId

            path.add(issue.localId)

            // Visit parent first (parents must exist before children), then dependencies
            val predecessors = listOfNotNull(issue.parent) + issue.dependsOn
            for (predecessorId in predecessors) {
                val predecessor = issueMap[predecessorId] ?: continue
                visit(predecessor)?.let { return it }
            }

            // Mark as visited and add to result
            visited.add(issue.localId)
            path.removeAt(path.lastIndex)
            result.add(issue)
            return null
        }

        // Visit all issues (handles disconnected components)
        for (issue in issues) {
            visit(issue)?.let { return SortResult.Cycle(it) }
        }

        return SortResult.Sorted(result)
    }
}
