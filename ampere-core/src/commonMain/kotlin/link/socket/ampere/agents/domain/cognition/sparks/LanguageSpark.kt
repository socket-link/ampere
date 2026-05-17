package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A language-specific spark authored from a `"language"` `.spark.md` fixture.
 *
 * Language sparks add idioms, conventions, and file-access narrowing for a
 * programming language. They do not normally narrow tools, so [allowedTools]
 * defaults to null and inherits the surrounding role.
 */
@Serializable
@SerialName("LanguageSpark")
data class LanguageSpark(
    val languageId: String,
    val displayName: String,
    override val promptContribution: String,
    override val phaseContributions: Map<CognitivePhase, String> = emptyMap(),
    override val requestedToolIds: Set<ToolId> = emptySet(),
    override val allowedTools: Set<ToolId>? = null,
    override val fileAccessScope: FileAccessScope? = null,
) : Spark {
    override val name: String = displayName
}

internal fun DeclarativeSparkSource.Language.toLanguageSpark(): LanguageSpark =
    LanguageSpark(
        languageId = frontmatter.id,
        displayName = frontmatter.name,
        promptContribution = body,
        phaseContributions = phaseContributions,
        requestedToolIds = frontmatter.requestedToolIds,
        allowedTools = frontmatter.allowedTools,
        fileAccessScope = frontmatter.fileAccessScope?.toDomain(),
    )
