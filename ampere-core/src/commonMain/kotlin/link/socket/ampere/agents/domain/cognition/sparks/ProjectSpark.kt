package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A Spark that establishes the project context for an agent.
 *
 * ProjectSpark is typically the broadest specialization layer, providing context
 * about which codebase/project the agent is working in. All subsequent Sparks
 * build upon this foundation.
 *
 * The file access patterns default to reading anywhere in the repository but
 * writing nowhere—role Sparks are responsible for enabling write access to
 * appropriate file types.
 */
@Serializable
@SerialName("ProjectSpark")
data class ProjectSpark(
    /**
     * Unique identifier for the project (e.g., "ampere", "my-service").
     */
    val projectId: String,

    /**
     * Multi-line description of what the project is and does.
     * This helps the agent understand the purpose and scope of the codebase.
     */
    val projectDescription: String,

    /**
     * File path to the repository root.
     * Used for file access patterns and relative path resolution.
     */
    val repositoryRoot: String,

    /**
     * Optional description of coding conventions, patterns, and style guidelines.
     * When provided, helps the agent follow project-specific practices.
     */
    val conventions: String = "",
) : Spark {

    override val name: String = "Project:$projectId"

    override val promptContribution: String = buildString {
        appendLine("## Project Context")
        appendLine()
        appendLine("You are working in the **$projectId** project.")
        appendLine()
        appendLine("### Description")
        appendLine(projectDescription)
        appendLine()
        appendLine("### Repository Root")
        appendLine("`$repositoryRoot`")
        if (conventions.isNotBlank()) {
            appendLine()
            appendLine("### Conventions")
            appendLine(conventions)
        }
    }

    override val allowedTools: Set<ToolId>? = null // Inherits from parent

    override val fileAccessScope: FileAccessScope = FileAccessScope(
        readPatterns = setOf("**/*"),
        writePatterns = emptySet(), // Role Sparks enable writing
        forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
    )

    companion object {
        /**
         * Creates a ProjectSpark with minimal configuration.
         */
        fun simple(
            projectId: String,
            description: String,
            repositoryRoot: String,
        ): ProjectSpark = ProjectSpark(
            projectId = projectId,
            projectDescription = description,
            repositoryRoot = repositoryRoot,
        )
    }
}
