package link.socket.ampere.agents.execution.outcomes

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.environment.EnvironmentFileOperation

/**
 * Summary of code changes made during execution.
 */
@Serializable
data class ExecutionCodeChangesResults(
    /** List of files that were changed */
    val filesChanged: List<FileChange>,
    /** Commit message describing the changes */
    val commitMessage: String,
    /** Human-readable summary of the diff */
    val diffSummary: String,
)

/**
 * Details about a single file change.
 */
@Serializable
data class FileChange(
    /** Path to the file that was changed */
    val path: String,
    /** What operation was performed */
    val operation: EnvironmentFileOperation,
    /** Number of lines added */
    val linesAdded: Int,
    /** Number of lines removed */
    val linesRemoved: Int,
)
