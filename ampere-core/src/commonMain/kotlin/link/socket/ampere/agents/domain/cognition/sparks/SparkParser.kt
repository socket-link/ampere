package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.cognition.Spark

/**
 * Outcome of [parseSpark]. Either a parsed source declaration or a typed
 * error describing why parsing failed.
 */
internal sealed interface SparkParseResult {

    data class Ok(val source: DeclarativeSparkSource) : SparkParseResult

    data class Failed(val error: SparkParseError) : SparkParseResult
}

/**
 * Reasons a `.spark.md` document may fail to parse. The list is closed; callers
 * can rely on `when` being exhaustive.
 */
internal sealed interface SparkParseError {

    /** The document lacks any recognised frontmatter fence. */
    data object MissingFrontmatter : SparkParseError

    /**
     * The document opens with the legacy `---` YAML fence. Until AMPR-165
     * removes the legacy parser entirely, this surfaces as a typed error so
     * fixtures that didn't migrate are visible during rollout instead of
     * silently producing empty libraries.
     */
    data object DeprecatedYamlFrontmatter : SparkParseError

    /** A required frontmatter field is missing. */
    data class MissingRequiredField(val field: String) : SparkParseError

    /** Frontmatter is structurally invalid (e.g. malformed key/value line). */
    data class InvalidFrontmatter(val message: String) : SparkParseError

    /** JSON frontmatter could not be decoded by `kotlinx-serialization`. */
    data class InvalidJson(val message: String) : SparkParseError

    /** JSON frontmatter declared a `"type"` discriminator with no matching variant. */
    data class UnknownDiscriminator(val value: String) : SparkParseError

    /** Frontmatter declared a phase value that is not a [CognitivePhase] name. */
    data class InvalidPhase(val value: String) : SparkParseError

    /** Frontmatter parsed but the body is empty. */
    data object EmptyBody : SparkParseError
}

private const val FENCE = "---"
private const val JSON_OPEN_FENCE = "---json"

/**
 * Dedicated [Json] instance for spark frontmatter.
 *
 * - `classDiscriminator = "type"` matches the convention used by sibling
 *   discriminated unions in this codebase (e.g. repository / plug bundle).
 * - `ignoreUnknownKeys = false` — spark frontmatter is capability-bearing, so a
 *   misspelled `requestedToolIds` must fail loudly rather than silently drop.
 * - `encodeDefaults = true` keeps round-trip serialization stable, which the
 *   parse-equivalence test in AMPR-165 Wave 1 leans on.
 */
private val sparkJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = false
    encodeDefaults = true
}

/**
 * Parses a `.spark.md` document.
 *
 * The expected shape since AMPR-165 is a JSON frontmatter block fenced by
 * `---json` / `---`:
 *
 * ```
 * ---json
 * {
 *   "type": "phase",
 *   "id": "cooking-domain",
 *   ...
 * }
 * ---
 *
 * Body markdown here.
 * ```
 *
 * Documents that still open with a bare `---` fence are rejected with
 * [SparkParseError.DeprecatedYamlFrontmatter] so unmigrated fixtures surface
 * during rollout instead of producing a quietly-empty library.
 */
internal fun parseSpark(raw: String): SparkParseResult {
    val trimmed = raw.replace("\r\n", "\n").trimStart('\n', ' ', '\t')
    return when {
        trimmed.startsWith("$JSON_OPEN_FENCE\n") || trimmed == JSON_OPEN_FENCE ->
            parseJsonSpark(trimmed)
        trimmed.startsWith("$FENCE\n") || trimmed == FENCE ->
            SparkParseResult.Failed(SparkParseError.DeprecatedYamlFrontmatter)
        else ->
            SparkParseResult.Failed(SparkParseError.MissingFrontmatter)
    }
}

/**
 * Parses a `---json` / `---` fenced document. Assumes [trimmed] has already
 * been normalised (no `\r\n`, no leading whitespace) and starts with the
 * `---json` fence.
 */
private fun parseJsonSpark(trimmed: String): SparkParseResult {
    val afterOpenFence = trimmed.removePrefix(JSON_OPEN_FENCE).trimStart('\n')
    val closeIndex = findClosingFence(afterOpenFence)
        ?: return SparkParseResult.Failed(SparkParseError.MissingFrontmatter)

    val jsonBlock = afterOpenFence.substring(0, closeIndex).trimEnd('\n')
    val body = afterOpenFence.substring(closeIndex)
        .removePrefix(FENCE)
        .trim('\n', ' ', '\t')

    val frontmatter = try {
        sparkJson.decodeFromString(SparkFrontmatter.serializer(), jsonBlock)
    } catch (ex: SerializationException) {
        return SparkParseResult.Failed(classifyJsonError(ex))
    }

    if (body.isEmpty()) {
        return SparkParseResult.Failed(SparkParseError.EmptyBody)
    }

    return when (frontmatter) {
        is PhaseSparkFrontmatter -> {
            val (baseBody, phaseContributions) = extractPhaseSections(body)
            SparkParseResult.Ok(
                DeclarativeSparkSource.Phase(
                    frontmatter = frontmatter,
                    body = baseBody,
                    phaseContributions = phaseContributions,
                ),
            )
        }
        is RoleSparkFrontmatter -> {
            // Role sparks keep their body as a single block — no `## When <Phase>`
            // extraction. Role guidance applies uniformly across phases by design.
            SparkParseResult.Ok(
                DeclarativeSparkSource.Role(
                    frontmatter = frontmatter,
                    body = body,
                ),
            )
        }
        is LanguageSparkFrontmatter -> {
            val (baseBody, phaseContributions) = extractPhaseSections(body)
            SparkParseResult.Ok(
                DeclarativeSparkSource.Language(
                    frontmatter = frontmatter,
                    body = baseBody,
                    phaseContributions = phaseContributions,
                ),
            )
        }
        is ProjectSparkFrontmatter -> {
            SparkParseResult.Ok(
                DeclarativeSparkSource.Project(
                    frontmatter = frontmatter,
                    body = body,
                ),
            )
        }
    }
}

/**
 * Maps a [SerializationException] thrown by [sparkJson] into the matching
 * [SparkParseError] variant. Keeping the mapping in one place lets the parser
 * surface stable, typed errors without leaking kotlinx-serialization's
 * exception hierarchy to callers.
 */
private fun classifyJsonError(ex: SerializationException): SparkParseError {
    val message = ex.message.orEmpty()
    return when {
        // kotlinx-serialization's SerializerNotFoundException message contains the
        // discriminator value. Format example:
        //   "Serializer for subclass 'unknown' is not found in the polymorphic scope of 'SparkFrontmatter'."
        message.contains("polymorphic", ignoreCase = true) ||
            message.contains("discriminator", ignoreCase = true) ||
            message.contains("subclass", ignoreCase = true) ->
            SparkParseError.UnknownDiscriminator(extractDiscriminatorValue(message))
        // MissingFieldException carries the missing field name.
        message.contains("missing", ignoreCase = true) && message.contains("field", ignoreCase = true) ->
            SparkParseError.MissingRequiredField(extractMissingFieldName(message))
        // CognitivePhase decoding failure surfaces the unrecognised enum string.
        (message.contains("CognitivePhase") || message.contains("enum", ignoreCase = true)) &&
            message.contains("does not contain element", ignoreCase = true) ->
            SparkParseError.InvalidPhase(extractEnumValue(message))
        else -> SparkParseError.InvalidJson(message)
    }
}

private val DISCRIMINATOR_QUOTE_REGEX = Regex("[`']([^`']+)[`']")

private fun extractDiscriminatorValue(message: String): String =
    DISCRIMINATOR_QUOTE_REGEX.find(message)?.groupValues?.getOrNull(1) ?: "<unknown>"

private val MISSING_FIELD_REGEX = Regex("[`']([A-Za-z_][A-Za-z0-9_]*)[`']")

private fun extractMissingFieldName(message: String): String =
    MISSING_FIELD_REGEX.find(message)?.groupValues?.getOrNull(1) ?: "<unknown>"

private val ENUM_VALUE_REGEX = Regex("contain element with name [`']([^`']+)[`']", RegexOption.IGNORE_CASE)

private fun extractEnumValue(message: String): String =
    ENUM_VALUE_REGEX.find(message)?.groupValues?.getOrNull(1) ?: "<unknown>"

/**
 * Splits a spark body into the always-on base content plus per-phase sections,
 * by delegating to the public [Spark.fromMarkdown].
 *
 * The delegation is the point: the `## When <Phase>` heading rules have exactly one
 * definition, so a bundled fixture and a consumer's own `.spark.md` cannot drift apart.
 * Only the split is wanted here — the frontmatter has already been decoded against the
 * typed [SparkFrontmatter] schema, which says strictly more than [Spark.fromMarkdown]'s
 * flat key/value map can.
 */
private fun extractPhaseSections(body: String): Pair<String, Map<CognitivePhase, String>> {
    val split = Spark.fromMarkdown(id = SECTION_SPLIT_ONLY_ID, body = body)
    return split.promptContribution to split.phaseContributions
}

/**
 * Placeholder name for the throwaway spark [extractPhaseSections] builds. The caller
 * reads only the body split off it; the real `name` comes from the frontmatter.
 */
private const val SECTION_SPLIT_ONLY_ID = "Spark:section-split"

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
