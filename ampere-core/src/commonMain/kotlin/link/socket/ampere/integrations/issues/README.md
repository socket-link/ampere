# Issue Tracker Provider Abstraction

This package defines the abstraction layer for integrating with external issue tracking systems.

## Architecture

The `IssueTrackerProvider` interface allows AMPERE agents to create and manage issues across different platforms without knowing platform-specific details.

```
Agent → ToolCreateIssues → IssueManagementExecutor → IssueTrackerProvider → Platform API
                                                             ↓
                                               (GitHub, Jira, Linear, etc.)
```

## Provider Interface

Implementations must provide:

1. **Identity**: `providerId` and `displayName`
2. **Connection**: `validateConnection()` for authentication checks
3. **CRUD Operations**:
   - `createIssue()`: Create individual issues
   - `updateIssue()`: Modify existing issues
   - `queryIssues()`: Search and retrieve issues
4. **Relationships**: `setParentRelationship()` for hierarchical issues

## Data Model

### IssueQuery
Search criteria for finding existing issues. All fields are optional and ANDed together.

**Example: Find open bugs assigned to user**
```kotlin
val query = IssueQuery(
    state = IssueState.Open,
    labels = listOf("bug"),
    assignee = "developer1"
)
```

### ExistingIssue
Read-only view of an issue from the tracking system.

**Example: Issue with parent relationship**
```kotlin
ExistingIssue(
    number = 43,
    title = "Implement user authentication",
    body = "Task details...",
    state = IssueState.Open,
    labels = listOf("feature", "backend"),
    url = "https://github.com/owner/repo/issues/43",
    parentNumber = 42  // Part of epic #42
)
```

### IssueUpdate
Partial update for modifying issues. Null fields are not changed.

**Example: Close issue and add label**
```kotlin
val update = IssueUpdate(
    state = IssueState.Closed,
    labels = listOf("resolved", "fixed")
)
```

## Implementing a Provider

### 1. Create Provider Class

```kotlin
class GitHubIssueProvider(
    private val token: String
) : IssueTrackerProvider {

    override val providerId = "github"
    override val displayName = "GitHub Issues"

    override suspend fun validateConnection(): Result<Unit> {
        // Check if token is valid and has necessary permissions
        // Make a test API call to verify access
    }

    override suspend fun createIssue(
        repository: String,
        request: IssueCreateRequest,
        resolvedDependencies: Map<String, Int>
    ): Result<CreatedIssue> {
        // 1. Build issue body with dependency references
        // 2. Call GitHub API to create issue
        // 3. Return CreatedIssue with number and URL
    }

    // ... implement other methods
}
```

### 2. Handle Platform Specifics

Each platform has different mechanisms for parent-child relationships:

**GitHub**: Add "Part of #parent" to issue body, use project boards
```kotlin
override suspend fun setParentRelationship(
    repository: String,
    childIssueNumber: Int,
    parentIssueNumber: Int
): Result<Unit> {
    // Update child issue body to reference parent
    val update = IssueUpdate(
        body = "Part of #$parentIssueNumber\n\nOriginal description..."
    )
    return updateIssue(repository, childIssueNumber, update)
        .map { Unit }
}
```

**Jira**: Use native parent/subtask field
```kotlin
override suspend fun setParentRelationship(
    repository: String,
    childIssueNumber: Int,
    parentIssueNumber: Int
): Result<Unit> {
    // Use Jira's parent field
    return jiraClient.setParent(childIssueNumber, parentIssueNumber)
}
```

### 3. Map Between Models

Transform AMPERE's `IssueCreateRequest` to platform-specific format:

```kotlin
private fun toGitHubIssue(request: IssueCreateRequest): GitHubIssueRequest {
    return GitHubIssueRequest(
        title = request.title,
        body = buildBody(request),
        labels = request.labels,
        assignees = request.assignees
    )
}

private fun buildBody(request: IssueCreateRequest): String {
    var body = request.body

    // Add dependency references
    if (request.dependsOn.isNotEmpty()) {
        val deps = request.dependsOn.joinToString(", ") { "#$it" }
        body += "\n\n**Depends on:** $deps"
    }

    return body
}
```

## Error Handling

All methods return `Result<T>` for proper error propagation:

```kotlin
override suspend fun createIssue(...): Result<CreatedIssue> {
    return try {
        val response = apiClient.createIssue(...)
        Result.success(response.toCreatedIssue())
    } catch (e: HttpException) {
        when (e.code) {
            401 -> Result.failure(Exception("Authentication failed"))
            403 -> Result.failure(Exception("Insufficient permissions"))
            404 -> Result.failure(Exception("Repository not found"))
            422 -> Result.failure(Exception("Validation failed: ${e.message}"))
            else -> Result.failure(e)
        }
    }
}
```

## Batch Issue Creation

The `resolvedDependencies` parameter enables batch creation with proper dependency ordering:

```kotlin
// During batch creation:
// 1. Create epic (no dependencies)
val epic = provider.createIssue(repo, epicRequest, emptyMap())
val epicNumber = epic.getOrThrow().issueNumber

// 2. Create first task (depends on epic)
val dependencies1 = mapOf("epic-1" to epicNumber)
val task1 = provider.createIssue(repo, task1Request, dependencies1)
val task1Number = task1.getOrThrow().issueNumber

// 3. Create second task (depends on epic and task1)
val dependencies2 = mapOf(
    "epic-1" to epicNumber,
    "task-1" to task1Number
)
val task2 = provider.createIssue(repo, task2Request, dependencies2)
```

## Next Steps

See `GitHubIssueProvider` (task AMP-302.3) for a complete reference implementation.
