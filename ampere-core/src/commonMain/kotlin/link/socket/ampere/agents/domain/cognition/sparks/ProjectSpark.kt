package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId
import link.socket.ampere.util.getEnvironmentVariable

/**
 * A Spark that establishes the project context for an agent.
 *
 * ProjectSpark is typically the broadest specialization layer, providing context
 * about which codebase/project the agent is working in. All subsequent Sparks
 * build upon this foundation.
 *
 * The file access patterns default to reading anywhere in the repository but
 * writing nowhere—role sparks are responsible for enabling write access to
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
        writePatterns = emptySet(), // Role sparks enable writing
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

internal fun DeclarativeSparkSource.Project.toProjectSpark(): ProjectSpark {
    val sections = parseProjectSparkBody(body)
    return ProjectSpark(
        projectId = frontmatter.projectId,
        projectDescription = sections.description,
        repositoryRoot = interpolateSparkString(frontmatter.repositoryRoot),
        conventions = sections.conventions,
    )
}

private data class ProjectSparkBodySections(
    val description: String,
    val conventions: String,
)

private fun parseProjectSparkBody(body: String): ProjectSparkBodySections {
    val lines = body.lines()
    val descriptionIndex = lines.indexOf(PROJECT_DESCRIPTION_HEADER)
    val conventionsIndex = lines.indexOf(PROJECT_CONVENTIONS_HEADER)
    require(descriptionIndex >= 0) {
        "Project spark '$PROJECT_DESCRIPTION_HEADER' section is required"
    }
    require(conventionsIndex > descriptionIndex) {
        "Project spark '$PROJECT_CONVENTIONS_HEADER' section is required after description"
    }
    return ProjectSparkBodySections(
        description = lines.subList(descriptionIndex + 1, conventionsIndex)
            .joinToString("\n")
            .trim('\n', ' ', '\t'),
        conventions = lines.subList(conventionsIndex + 1, lines.size)
            .joinToString("\n")
            .trim('\n', ' ', '\t'),
    )
}

private const val PROJECT_DESCRIPTION_HEADER = "## Project Description"
private const val PROJECT_CONVENTIONS_HEADER = "## Project Conventions"

internal fun interpolateSparkString(value: String): String {
    if (!value.contains(ENV_INTERPOLATION_PREFIX)) return value
    return buildString {
        var index = 0
        while (index < value.length) {
            val start = value.indexOf(ENV_INTERPOLATION_PREFIX, startIndex = index)
            if (start < 0) {
                append(value.substring(index))
                break
            }
            append(value.substring(index, start))
            val end = findInterpolationEnd(value, start)
            if (end < 0) {
                append(value.substring(start))
                break
            }
            val expression = value.substring(start + ENV_INTERPOLATION_PREFIX.length, end)
            append(resolveEnvExpression(expression))
            index = end + 1
        }
    }
}

private const val ENV_INTERPOLATION_PREFIX = "\${env:"

private fun findInterpolationEnd(value: String, start: Int): Int {
    var depth = 0
    var index = start
    while (index < value.length) {
        if (value.startsWith("\${", index)) {
            depth += 1
            index += 2
            continue
        }
        if (value[index] == '}') {
            depth -= 1
            if (depth == 0) return index
        }
        index += 1
    }
    return -1
}

private fun resolveEnvExpression(expression: String): String {
    val fallbackStart = findTopLevelFallbackStart(expression)
    val variableName = if (fallbackStart < 0) {
        expression
    } else {
        expression.substring(0, fallbackStart)
    }.trim()
    val envValue = runCatching { getEnvironmentVariable(variableName) }.getOrNull()
    if (!envValue.isNullOrBlank()) return envValue

    return if (fallbackStart < 0) {
        ""
    } else {
        interpolateSparkString(expression.substring(fallbackStart + 2))
    }
}

private fun findTopLevelFallbackStart(expression: String): Int {
    var depth = 0
    var index = 0
    while (index < expression.length - 1) {
        if (expression.startsWith("\${", index)) {
            depth += 1
            index += 2
            continue
        }
        if (expression[index] == '}') {
            depth -= 1
        }
        if (depth == 0 && expression[index] == ':' && expression[index + 1] == '-') {
            return index
        }
        index += 1
    }
    return -1
}
