package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope

/**
 * Sealed schema for the JSON frontmatter that opens every `.spark.md` document.
 *
 * The fence is `---json` / `---`; the body between fences must decode to a
 * variant of [SparkFrontmatter] via a [kotlinx.serialization.json.Json] instance
 * configured with `classDiscriminator = "type"`, `ignoreUnknownKeys = false`,
 * and `encodeDefaults = true`. Unknown fields are a parse error by intent —
 * spark frontmatter is capability-bearing, and silently dropping a misspelled
 * `requestedToolIds` would let a spark ship without the tools it expects.
 *
 * The two production variants today are [PhaseSparkFrontmatter] (`"phase"`)
 * and [RoleSparkFrontmatter] (`"role"`). Future variants
 * (`"project"`, `"language"`, `"task"`, `"coordination"`) plug into the same
 * discriminator without touching call sites that consume the sealed type.
 */
@Serializable
internal sealed interface SparkFrontmatter {
    val id: String
    val name: String
}

/**
 * Frontmatter shape for a phase-oriented spark — prompt-only, no
 * capability narrowing. Mirrors the historical YAML fields of
 * [DeclarativePhaseSparkSource] one-for-one so the migration is a fence
 * rewrite, not a schema overhaul.
 */
@Serializable
@SerialName("phase")
internal data class PhaseSparkFrontmatter(
    override val id: String,
    override val name: String,
    val whenToUse: String,
    val phases: Set<CognitivePhase> = setOf(CognitivePhase.PLAN),
    val tags: Set<String> = emptySet(),
    val modelPreference: String? = null,
    val agentRole: String? = null,
    val requestedToolIds: Set<String> = emptySet(),
) : SparkFrontmatter

/**
 * Frontmatter shape for a role spark — capability-bearing. Carries the tool
 * narrowing and file-access scope that built-in role sparks (e.g.
 * [RoleSpark.Code]) encode in Kotlin today. The body of the spark document
 * becomes the role's `promptContribution` directly; there is no per-phase
 * section extraction for role variants.
 *
 * `allowedTools = null` preserves the "inherits from parent context" semantics
 * documented on [link.socket.ampere.agents.domain.cognition.Spark.allowedTools];
 * an empty set explicitly narrows to "no tools allowed" and is **not** the same
 * as omitting the field.
 */
@Serializable
@SerialName("role")
internal data class RoleSparkFrontmatter(
    override val id: String,
    override val name: String,
    val agentRole: String,
    val requestedToolIds: Set<String> = emptySet(),
    val allowedTools: Set<String>? = null,
    val fileAccessScope: FileAccessScopeFrontmatter? = null,
) : SparkFrontmatter

/**
 * Glob-pattern bundle for [FileAccessScope], shaped for ergonomic JSON
 * authoring (one field per pattern axis).
 *
 * Empty sets in JSON map directly to empty sets in the domain object; the
 * domain class already encodes "empty means no access unless inherited from
 * parent" via its companion defaults.
 */
@Serializable
internal data class FileAccessScopeFrontmatter(
    val read: Set<String> = emptySet(),
    val write: Set<String> = emptySet(),
    val forbidden: Set<String> = emptySet(),
) {
    fun toDomain(): FileAccessScope = FileAccessScope(
        readPatterns = read,
        writePatterns = write,
        forbiddenPatterns = forbidden,
    )
}
