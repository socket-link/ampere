package link.socket.ampere.agents.execution.tools.issue

import kotlinx.serialization.Serializable

/**
 * Types of issues that can be created in a project management system.
 */
@Serializable
enum class IssueType {
    /** Epic-level work - large feature or initiative */
    Feature,

    /** Individual work item - concrete task to be completed */
    Task,

    /** Defect tracking - bug or error to be fixed */
    Bug,

    /** Research/investigation - exploratory work or proof of concept */
    Spike,
}

/**
 * Request to create a single issue in a project management system.
 *
 * @property localId Client-side identifier used for dependency resolution before real issue numbers exist
 * @property type The type of issue to create
 * @property title Brief summary of the issue (typically one line)
 * @property body Detailed description in markdown format
 * @property labels Tags/labels to apply to the issue
 * @property assignees Usernames of people to assign the issue to
 * @property parent localId of parent issue (for hierarchical issues like tasks under an epic)
 * @property dependsOn localIds of issues that must be completed before this one
 */
@Serializable
data class IssueCreateRequest(
    val localId: String,
    val type: IssueType,
    val title: String,
    val body: String,
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val parent: String? = null,
    val dependsOn: List<String> = emptyList(),
)

/**
 * Request to create multiple issues in batch, with support for hierarchies and dependencies.
 *
 * @property repository Repository identifier in "owner/repo" format
 * @property issues List of issues to create, may include parent-child relationships
 */
@Serializable
data class BatchIssueCreateRequest(
    val repository: String,
    val issues: List<IssueCreateRequest>,
)

/**
 * Result of successfully creating a single issue.
 *
 * @property localId The client-side identifier that was used in the request
 * @property issueNumber The actual issue number assigned by the project management system
 * @property url Full URL to view the created issue
 * @property parentIssueNumber The issue number of the parent (if this was a child issue)
 */
@Serializable
data class CreatedIssue(
    val localId: String,
    val issueNumber: Int,
    val url: String,
    val parentIssueNumber: Int? = null,
)

/**
 * Error that occurred while attempting to create an issue.
 *
 * @property localId The client-side identifier of the issue that failed to create
 * @property message Description of what went wrong
 */
@Serializable
data class IssueCreateError(
    val localId: String,
    val message: String,
)

/**
 * Response from a batch issue creation request.
 *
 * @property success True if all issues were created successfully
 * @property created List of successfully created issues
 * @property errors List of errors that occurred during creation
 */
@Serializable
data class BatchIssueCreateResponse(
    val success: Boolean,
    val created: List<CreatedIssue>,
    val errors: List<IssueCreateError>,
)
