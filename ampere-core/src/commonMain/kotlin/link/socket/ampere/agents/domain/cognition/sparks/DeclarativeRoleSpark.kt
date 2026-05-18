package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A capability-bearing role spark authored as a JSON-frontmatter `.spark.md`
 * document instead of a sealed Kotlin role singleton.
 *
 * Mirrors [DeclarativePhaseSpark] for the role axis: the runtime spark
 * surfaces its body as a single [promptContribution] block (no
 * `## When <Phase>` extraction) and exposes [allowedTools] /
 * [fileAccessScope] verbatim from the parsed frontmatter. Capability
 * narrowing against the surrounding stack is the existing
 * [link.socket.ampere.agents.domain.cognition.SparkStack.effectiveAllowedTools]
 * / [link.socket.ampere.agents.domain.cognition.SparkStack.effectiveFileAccess]
 * intersection semantics — no new composition logic here.
 *
 * `name` is delegated to the frontmatter so trace projections keep the
 * historical `Role:<Subtype>` bucket: the seed fixture declares
 * `"name": "Role:Code"`, matching the retired Kotlin singleton's trace
 * label at the [link.socket.ampere.agents.definition.SparkBasedAgent]
 * factory callsites.
 */
@Serializable
@SerialName("Role.Declarative")
internal data class DeclarativeRoleSpark(
    val sparkId: String,
    val displayName: String,
    override val promptContribution: String,
    override val agentRole: String?,
    override val requestedToolIds: Set<ToolId>,
    override val allowedTools: Set<ToolId>?,
    override val fileAccessScope: FileAccessScope?,
) : Spark {
    override val name: String = displayName
}

/**
 * Converts a parsed [DeclarativeSparkSource.Role] into the runtime spark the
 * factory callsites push onto a stack. Pure mapping; capability composition
 * lives on the stack, not here.
 */
internal fun DeclarativeSparkSource.Role.toRoleSpark(): DeclarativeRoleSpark =
    DeclarativeRoleSpark(
        sparkId = frontmatter.id,
        displayName = frontmatter.name,
        promptContribution = body,
        agentRole = frontmatter.agentRole,
        requestedToolIds = frontmatter.requestedToolIds,
        allowedTools = frontmatter.allowedTools,
        fileAccessScope = frontmatter.fileAccessScope?.toDomain(),
    )
