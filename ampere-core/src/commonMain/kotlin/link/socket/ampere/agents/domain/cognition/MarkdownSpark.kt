package link.socket.ampere.agents.domain.cognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

/**
 * A [Spark] authored as markdown, with no frontmatter schema behind it.
 *
 * This is what [Spark.fromMarkdown] returns: the public, consumer-facing shape of
 * "a `.spark.md` body became a Spark". It carries no capability narrowing
 * ([allowedTools] and [fileAccessScope] are always null) because a body of markdown
 * cannot express a permission — narrowing is declared in frontmatter and composed by
 * [SparkStack], and a spark the caller built out of prose must not be able to widen
 * anything.
 *
 * Construct it through [Spark.fromMarkdown] rather than directly, so the
 * `## When <Phase>` heading rules stay in one place.
 */
@Serializable
@SerialName("Spark.Markdown")
data class MarkdownSpark(
    override val name: String,
    override val promptContribution: String,
    override val phaseContributions: Map<CognitivePhase, String> = emptyMap(),
    override val agentRole: String? = null,
    override val requestedToolIds: Set<ToolId> = emptySet(),
) : Spark {
    override val allowedTools: Set<ToolId>? = null
    override val fileAccessScope: FileAccessScope? = null
}

/** Frontmatter key read for [Spark.requestedToolIds]. Comma- or whitespace-separated. */
internal const val MARKDOWN_SPARK_TOOLS_KEY = "tools"

/** Frontmatter key read for [Spark.agentRole], honoured only when the caller opts in. */
internal const val MARKDOWN_SPARK_ROLE_KEY = "role"

/**
 * The six `## When <Phase>` headings a markdown spark body may carry, in PROPEL order.
 *
 * Exactly these, matched case-insensitively. Every other `## When …` heading is prose
 * and stays in the body — see [Spark.fromMarkdown].
 */
private val PHASE_HEADINGS: Map<String, CognitivePhase> = mapOf(
    "perceiving" to CognitivePhase.PERCEIVE,
    "recalling" to CognitivePhase.RECALL,
    "observing" to CognitivePhase.OBSERVE,
    "planning" to CognitivePhase.PLAN,
    "executing" to CognitivePhase.EXECUTE,
    "learning" to CognitivePhase.LEARN,
)

private val PHASE_HEADING_REGEX = Regex(
    "^\\s*##\\s+When\\s+(\\S+)\\s*$",
    RegexOption.IGNORE_CASE,
)

/**
 * The split of a markdown spark body into always-on content and per-phase sections.
 */
internal data class MarkdownSparkBody(
    val base: String,
    val phaseContributions: Map<CognitivePhase, String>,
)

/**
 * Splits a markdown spark body on the six `## When <Phase>` headings.
 *
 * One definition of the heading rules, shared by [Spark.fromMarkdown] and the internal
 * `.spark.md` parser, so a bundled fixture and a consumer's document split identically.
 */
internal fun splitMarkdownSparkBody(body: String): MarkdownSparkBody {
    val baseLines = mutableListOf<String>()
    val sections = linkedMapOf<CognitivePhase, MutableList<String>>()
    var open: CognitivePhase? = null

    for (line in body.replace("\r\n", "\n").lines()) {
        if (isLevelTwoHeading(line)) {
            val phase = matchPhaseHeading(line)
            if (phase != null) {
                // A repeated heading appends to the section already opened for that phase.
                open = phase
                sections.getOrPut(phase) { mutableListOf() }
                continue
            }
            // Any other level-two heading closes the open section and returns to the
            // always-on body, heading line included. A level-three heading does not:
            // `### …` is structure *inside* whichever section is open.
            open = null
            baseLines += line
            continue
        }
        if (open == null) {
            baseLines += line
        } else {
            sections.getValue(open) += line
        }
    }

    return MarkdownSparkBody(
        base = baseLines.joinToString("\n").trim('\n', ' ', '\t'),
        phaseContributions = sections.mapValues { (_, lines) ->
            lines.joinToString("\n").trim('\n', ' ', '\t')
        },
    )
}

private fun isLevelTwoHeading(line: String): Boolean {
    val start = line.trimStart()
    return start.startsWith("##") && !start.startsWith("###")
}

private fun matchPhaseHeading(line: String): CognitivePhase? {
    val match = PHASE_HEADING_REGEX.matchEntire(line) ?: return null
    return PHASE_HEADINGS[match.groupValues[1].lowercase()]
}

/**
 * Splits a `tools` frontmatter value into tool ids.
 *
 * Commas and whitespace both separate, because a [ToolId] contains neither, so
 * `"read_code_file, write_code_file"` and `"read_code_file write_code_file"` both work.
 */
internal fun parseMarkdownSparkToolIds(value: String): Set<ToolId> =
    value.split(',', ' ', '\t', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
