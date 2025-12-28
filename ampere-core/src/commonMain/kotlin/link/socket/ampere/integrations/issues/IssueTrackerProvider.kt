package link.socket.ampere.integrations.issues

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest

/**
 * Abstraction over issue tracking systems.
 *
 * Implementations exist for GitHub, Jira, Linear, etc.
 * The provider handles authentication, API specifics, and
 * mapping between AMPERE's issue model and the platform's model.
 *
 * This follows the same pattern as AI provider abstraction—agents work
 * with a common interface while concrete implementations handle
 * platform-specific details.
 */
interface IssueTrackerProvider {

    /** Unique identifier for this provider type (e.g., "github", "jira", "linear") */
    val providerId: String

    /** Human-readable name (e.g., "GitHub Issues", "Jira Cloud") */
    val displayName: String

    /**
     * Check if provider is properly configured and authenticated.
     *
     * This validates that credentials are present, valid, and have
     * the necessary permissions to create and manage issues.
     *
     * @return Success if connection is valid, Failure with details otherwise
     */
    suspend fun validateConnection(): Result<Unit>

    /**
     * Create a single issue in the tracking system.
     *
     * @param repository Repository identifier (format depends on provider)
     * @param request The issue to create
     * @param resolvedDependencies Map of localId to actual issue numbers for dependencies
     * @return Success with created issue details, or Failure with error
     */
    suspend fun createIssue(
        repository: String,
        request: IssueCreateRequest,
        resolvedDependencies: Map<String, Int>, // localId -> issueNumber
    ): Result<CreatedIssue>

    /**
     * Establish parent-child relationship between issues.
     *
     * The mechanism varies by provider:
     * - GitHub: Add "Part of #parent" to issue body, link in project boards
     * - Jira: Set "Parent" field or use Epic link
     * - Linear: Set parent relationship via API
     *
     * @param repository Repository identifier
     * @param childIssueNumber Issue number of the child
     * @param parentIssueNumber Issue number of the parent
     * @return Success if relationship was established, Failure otherwise
     */
    suspend fun setParentRelationship(
        repository: String,
        childIssueNumber: Int,
        parentIssueNumber: Int,
    ): Result<Unit>

    /**
     * Query existing issues.
     *
     * Useful for duplicate detection, finding related work, and
     * gathering context before creating new issues.
     *
     * @param repository Repository identifier
     * @param query Search criteria
     * @return Success with matching issues, or Failure with error
     */
    suspend fun queryIssues(
        repository: String,
        query: IssueQuery,
    ): Result<List<ExistingIssue>>

    /**
     * Update an existing issue.
     *
     * @param repository Repository identifier
     * @param issueNumber Issue number to update
     * @param update Fields to update (null fields are not changed)
     * @return Success with updated issue, or Failure with error
     */
    suspend fun updateIssue(
        repository: String,
        issueNumber: Int,
        update: IssueUpdate,
    ): Result<ExistingIssue>

    /**
     * Add a summary of child issues to a parent issue.
     *
     * This is an optional enhancement that providers can implement to maintain
     * better visibility of parent-child relationships. For example, GitHub can
     * add a comment with checkboxes for each child issue.
     *
     * Default implementation is a no-op. Providers that support this feature
     * (like GitHubCliProvider) should override to add summaries.
     *
     * @param repository Repository identifier
     * @param parentNumber Issue number of the parent
     * @param children All created issues that are children of this parent
     * @return Success if summary was added (or not needed), Failure on error
     */
    suspend fun summarizeChildren(
        repository: String,
        parentNumber: Int,
        children: List<CreatedIssue>,
    ): Result<Unit> = Result.success(Unit)
}

/**
 * Query criteria for searching existing issues.
 *
 * All fields are optional and ANDed together.
 * Providers should do their best to match these criteria,
 * but exact semantics may vary by platform.
 */
@Serializable
data class IssueQuery(
    /** Filter by issue state (null means all states) */
    val state: IssueState? = null,

    /** Filter by labels (issue must have all these labels) */
    val labels: List<String> = emptyList(),

    /** Filter by assignee username (null means any assignee) */
    val assignee: String? = null,

    /** Filter by title substring (case-insensitive) */
    val titleContains: String? = null,

    /** Maximum number of results to return */
    val limit: Int = 50,
)

/**
 * Issue state in the tracking system.
 */
@Serializable
enum class IssueState {
    /** Issue is open/active */
    Open,

    /** Issue is closed/resolved */
    Closed,

    /** Query all issues regardless of state */
    All,
}

/**
 * Represents an existing issue retrieved from the tracking system.
 *
 * This is a read-only view of an issue, distinct from IssueCreateRequest
 * which is used for creating new issues.
 */
@Serializable
data class ExistingIssue(
    /** Issue number in the tracking system */
    val number: Int,

    /** Issue title */
    val title: String,

    /** Issue body/description in markdown */
    val body: String,

    /** Current state of the issue */
    val state: IssueState,

    /** Labels/tags applied to the issue */
    val labels: List<String>,

    /** Full URL to view the issue */
    val url: String,

    /** Parent issue number if this is a child issue */
    val parentNumber: Int? = null,
)

/**
 * Update request for modifying an existing issue.
 *
 * All fields are optional—only non-null fields will be updated.
 * This allows partial updates without needing to fetch the current
 * state first.
 */
@Serializable
data class IssueUpdate(
    /** New title (null = don't change) */
    val title: String? = null,

    /** New body (null = don't change) */
    val body: String? = null,

    /** New state (null = don't change) */
    val state: IssueState? = null,

    /** New labels (null = don't change, empty list = clear all labels) */
    val labels: List<String>? = null,

    /** New assignees (null = don't change, empty list = unassign all) */
    val assignees: List<String>? = null,
)
