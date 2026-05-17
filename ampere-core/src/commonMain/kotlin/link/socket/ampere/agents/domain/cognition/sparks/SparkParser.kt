package link.socket.ampere.agents.domain.cognition.sparks

/**
 * Outcome of [parseSpark]. Either a parsed source declaration or a typed
 * error describing why parsing failed.
 */
internal sealed interface SparkParseResult {

    data class Ok(val source: DeclarativePhaseSparkSource) : SparkParseResult

    data class Failed(val error: SparkParseError) : SparkParseResult
}

/**
 * Reasons a `.spark.md` document may fail to parse. The list is closed; callers
 * can rely on `when` being exhaustive.
 */
internal sealed interface SparkParseError {

    /** The document lacks the required `---` frontmatter fence. */
    data object MissingFrontmatter : SparkParseError

    /** A required frontmatter field is missing. */
    data class MissingRequiredField(val field: String) : SparkParseError

    /** Frontmatter is structurally invalid (e.g. malformed key/value line). */
    data class InvalidFrontmatter(val message: String) : SparkParseError

    /** Frontmatter declared a phase value that is not a [CognitivePhase] name. */
    data class InvalidPhase(val value: String) : SparkParseError

    /** Frontmatter parsed but the body is empty. */
    data object EmptyBody : SparkParseError
}

private const val FENCE = "---"

/**
 * Parses a `.spark.md` document into a [DeclarativePhaseSparkSource].
 *
 * Expected shape:
 * ```
 * ---
 * id: cooking-domain
 * name: Cooking Domain
 * whenToUse: tasks that mention recipes, ingredients, or meal planning
 * phases: PLAN, EXECUTE
 * tags: cooking, recipes
 * modelPreference: gpt-4o-mini
 * ---
 *
 * Body markdown here.
 * ```
 *
 * Hand-rolled (no Kaml dependency). Values are everything after the first `:`
 * on a line; comma-separated lists are split for `phases` and `tags`. If a
 * single key requires multi-line value support, **stop and file a
 * Kaml-in-commonMain follow-up** before extending this parser.
 */
internal fun parseSpark(raw: String): SparkParseResult {
    val normalized = raw.replace("\r\n", "\n")
    val trimmedStart = normalized.trimStart('\n', ' ', '\t')

    if (!trimmedStart.startsWith("$FENCE\n") && trimmedStart != FENCE && !trimmedStart.startsWith("$FENCE\r")) {
        return SparkParseResult.Failed(SparkParseError.MissingFrontmatter)
    }

    val afterOpenFence = trimmedStart.removePrefix(FENCE).trimStart('\n')
    val closeIndex = findClosingFence(afterOpenFence)
        ?: return SparkParseResult.Failed(SparkParseError.MissingFrontmatter)

    val frontmatterBlock = afterOpenFence.substring(0, closeIndex).trimEnd('\n')
    val body = afterOpenFence.substring(closeIndex)
        .removePrefix(FENCE)
        .trim('\n', ' ', '\t')

    val fields = mutableMapOf<String, String>()
    for ((lineNumber, rawLine) in frontmatterBlock.lines().withIndex()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        val sepIndex = line.indexOf(':')
        if (sepIndex <= 0) {
            return SparkParseResult.Failed(
                SparkParseError.InvalidFrontmatter("line ${lineNumber + 1}: expected 'key: value'"),
            )
        }
        val key = line.substring(0, sepIndex).trim()
        val value = line.substring(sepIndex + 1).trim()
        if (key.isEmpty()) {
            return SparkParseResult.Failed(
                SparkParseError.InvalidFrontmatter("line ${lineNumber + 1}: blank key"),
            )
        }
        fields[key] = value
    }

    val id = fields["id"]?.takeIf { it.isNotEmpty() }
        ?: return SparkParseResult.Failed(SparkParseError.MissingRequiredField("id"))
    val name = fields["name"]?.takeIf { it.isNotEmpty() }
        ?: return SparkParseResult.Failed(SparkParseError.MissingRequiredField("name"))
    val whenToUse = fields["whenToUse"]?.takeIf { it.isNotEmpty() }
        ?: return SparkParseResult.Failed(SparkParseError.MissingRequiredField("whenToUse"))

    val phases = when (val phasesRaw = fields["phases"]) {
        null, "" -> setOf(CognitivePhase.PLAN)
        else -> {
            val parsed = mutableSetOf<CognitivePhase>()
            for (token in phasesRaw.split(',')) {
                val trimmed = token.trim()
                if (trimmed.isEmpty()) continue
                val phase = parsePhase(trimmed)
                    ?: return SparkParseResult.Failed(SparkParseError.InvalidPhase(trimmed))
                parsed += phase
            }
            if (parsed.isEmpty()) setOf(CognitivePhase.PLAN) else parsed
        }
    }

    val tags = fields["tags"].splitCommaSeparated()
    val modelPreference = fields["modelPreference"]?.takeIf { it.isNotEmpty() }
    val agentRole = fields["agentRole"]?.takeIf { it.isNotEmpty() }
    val requestedToolIds = fields["requestedToolIds"].splitCommaSeparated()

    if (body.isEmpty()) {
        return SparkParseResult.Failed(SparkParseError.EmptyBody)
    }

    val (baseBody, phaseContributions) = extractPhaseSections(body)

    return SparkParseResult.Ok(
        DeclarativePhaseSparkSource(
            id = id,
            name = name,
            whenToUse = whenToUse,
            body = baseBody,
            phases = phases,
            tags = tags,
            modelPreference = modelPreference,
            phaseContributions = phaseContributions,
            agentRole = agentRole,
            requestedToolIds = requestedToolIds,
        ),
    )
}

/**
 * Splits a spark body into the always-on base content plus per-phase sections
 * introduced by `## When Perceiving`, `## When Planning`, `## When Executing`,
 * or `## When Learning` headers.
 *
 * Any text that appears before the first `## When <Phase>` header becomes the base
 * content. Sections terminate at the next `## When <Phase>` header or end-of-body.
 */
private fun extractPhaseSections(body: String): Pair<String, Map<CognitivePhase, String>> {
    val lines = body.lines()
    val baseLines = mutableListOf<String>()
    val phaseSections = mutableMapOf<CognitivePhase, MutableList<String>>()
    var currentPhase: CognitivePhase? = null

    for (line in lines) {
        val phaseFromHeader = matchPhaseHeader(line)
        if (phaseFromHeader != null) {
            currentPhase = phaseFromHeader
            phaseSections.getOrPut(phaseFromHeader) { mutableListOf() }
            continue
        }
        if (currentPhase == null) {
            baseLines += line
        } else {
            phaseSections.getValue(currentPhase) += line
        }
    }

    val base = baseLines.joinToString("\n").trim('\n', ' ', '\t')
    val finalized = phaseSections.mapValues { (_, sectionLines) ->
        sectionLines.joinToString("\n").trim('\n', ' ', '\t')
    }
    return base to finalized
}

private val PHASE_HEADER = Regex(
    "^\\s*##\\s+When\\s+(Perceiving|Planning|Executing|Learning)\\s*$",
    RegexOption.IGNORE_CASE,
)

private fun matchPhaseHeader(line: String): CognitivePhase? {
    val match = PHASE_HEADER.matchEntire(line) ?: return null
    return when (match.groupValues[1].lowercase()) {
        "perceiving" -> CognitivePhase.PERCEIVE
        "planning" -> CognitivePhase.PLAN
        "executing" -> CognitivePhase.EXECUTE
        "learning" -> CognitivePhase.LEARN
        else -> null
    }
}

private fun findClosingFence(text: String): Int? {
    var searchStart = 0
    while (true) {
        val candidate = text.indexOf(FENCE, startIndex = searchStart)
        if (candidate == -1) return null
        val precededByNewline = candidate == 0 || text[candidate - 1] == '\n'
        val followedByEnd = candidate + FENCE.length == text.length
        val followedByNewline = candidate + FENCE.length < text.length &&
            text[candidate + FENCE.length] == '\n'
        if (precededByNewline && (followedByEnd || followedByNewline)) {
            return candidate
        }
        searchStart = candidate + 1
    }
}

private fun parsePhase(value: String): CognitivePhase? =
    CognitivePhase.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

private fun String?.splitCommaSeparated(): Set<String> {
    if (this.isNullOrBlank()) return emptySet()
    return split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
