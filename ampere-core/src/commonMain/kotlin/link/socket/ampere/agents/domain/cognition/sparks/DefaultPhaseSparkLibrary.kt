package link.socket.ampere.agents.domain.cognition.sparks

import co.touchlab.kermit.Logger
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.resources.Res
import link.socket.ampere.util.logWith
import org.jetbrains.compose.resources.ExperimentalResourceApi

private val DEFAULT_SPARKS: List<String> = listOf(
    "files/sparks/cooking-domain.spark.md",
    "files/sparks/recipe-arc-task.spark.md",
    "files/sparks/minimal-edge.spark.md",
    "files/sparks/code-agent.spark.md",
    "files/sparks/product-agent.spark.md",
    "files/sparks/project-agent.spark.md",
    "files/sparks/quality-agent.spark.md",
    "files/sparks/role-code.spark.md",
    "files/sparks/role-research.spark.md",
    "files/sparks/role-operations.spark.md",
    "files/sparks/role-planning.spark.md",
    "files/sparks/language-kotlin.spark.md",
    "files/sparks/language-java.spark.md",
    "files/sparks/language-typescript.spark.md",
    "files/sparks/language-python.spark.md",
    "files/sparks/project-ampere.spark.md",
)

/**
 * In-memory [PhaseSparkLibrary] backed by a pre-parsed set of declarative sparks.
 *
 * Construction takes a fully-resolved list of sparks so the runtime accessors
 * (`all`, `byId`, `selectFor`, `roleSparkById`, `languageSparkById`,
 * `projectSparkById`) are non-suspend. Use
 * [DefaultPhaseSparkLibrary.load] to read and parse the bundled `.spark.md`
 * resources asynchronously.
 *
 * The loader mirrors the resource-loading shape of
 * [link.socket.ampere.domain.ai.pricing.BundledProviderPricingCatalog]: it tries
 * `Res.readBytes` first, then falls back to a platform-specific classpath
 * lookup so JVM unit tests work without the Compose resource graph. Parse
 * failures are logged via [logWith] and the offending spark is skipped — loading
 * never throws.
 */
internal class DefaultPhaseSparkLibrary internal constructor(
    private val phaseSparks: List<PhaseSpark>,
    private val roleSparks: Map<String, DeclarativeRoleSpark> = emptyMap(),
    private val languageSparks: Map<String, LanguageSpark> = emptyMap(),
    private val projectSparks: Map<String, ProjectSpark> = emptyMap(),
) : PhaseSparkLibrary {

    override fun all(): List<PhaseSpark> = phaseSparks

    override fun byId(id: PhaseSparkId): PhaseSpark? =
        phaseSparks.firstOrNull { spark -> spark is DeclarativePhaseSpark && spark.sparkId == id }

    override fun roleSparkById(id: String): Spark? = roleSparks[id]

    override fun languageSparkById(id: String): Spark? = languageSparks[id]

    override fun projectSparkById(id: String): ProjectSpark? = projectSparks[id]

    override fun selectFor(context: SparkSelectionContext): List<PhaseSpark> {
        val phaseMatches = phaseSparks.filterIsInstance<DeclarativePhaseSpark>()
            .filter { context.phase in it.eligiblePhases }

        if (phaseMatches.isEmpty()) return emptyList()

        val tagFiltered = if (context.tags.isEmpty()) {
            phaseMatches
        } else {
            phaseMatches.filter { spark -> spark.tags.any { it in context.tags } }
        }

        val keywords = extractKeywords(context.text)
        val keywordMatched = if (keywords.isEmpty()) {
            tagFiltered
        } else {
            tagFiltered.filter { spark ->
                val haystack = (spark.whenToUse + " " + spark.displayName).lowercase()
                keywords.any { keyword -> haystack.contains(keyword) }
            }
        }

        return keywordMatched.sortedBy { it.sparkId }
    }

    companion object {
        private val logger: Logger = logWith("PhaseSparkLibrary")
        private val KEYWORD_SPLITTERS: CharArray = charArrayOf(
            ' ', '\t', '\n', '\r', ',', '.', ';', ':', '!', '?', '/', '\\', '(', ')', '[', ']', '{', '}',
        )

        /**
         * Loads the bundled spark fixtures into an in-memory library.
         */
        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(
            sparkResourcePaths: List<String> = DEFAULT_SPARKS,
        ): DefaultPhaseSparkLibrary {
            val seenIds = mutableSetOf<String>()
            val phaseSparks = mutableListOf<PhaseSpark>()
            val roleSparks = mutableMapOf<String, DeclarativeRoleSpark>()
            val languageSparks = mutableMapOf<String, LanguageSpark>()
            val projectSparks = mutableMapOf<String, ProjectSpark>()
            for (path in sparkResourcePaths) {
                val raw = readResource(path)
                if (raw == null) {
                    logger.w { "[PhaseSparkLibrary] missing bundled spark resource: $path" }
                    continue
                }
                when (val parsed = parseSpark(raw)) {
                    is SparkParseResult.Ok -> {
                        val source = parsed.source
                        if (!seenIds.add(source.id)) {
                            logger.w {
                                "[PhaseSparkLibrary] duplicate spark id '${source.id}' from $path — skipping"
                            }
                        } else {
                            runCatching {
                                when (source) {
                                    is DeclarativeSparkSource.Phase ->
                                        phaseSparks += source.toLegacySource().toPhaseSpark()
                                    is DeclarativeSparkSource.Role ->
                                        roleSparks[source.id] = source.toRoleSpark()
                                    is DeclarativeSparkSource.Language ->
                                        languageSparks[source.id] = source.toLanguageSpark()
                                    is DeclarativeSparkSource.Project ->
                                        projectSparks[source.id] = source.toProjectSpark()
                                }
                            }.onFailure { error ->
                                logger.w(error) {
                                    "[PhaseSparkLibrary] failed to adapt $path into a runtime spark"
                                }
                            }
                        }
                    }
                    is SparkParseResult.Failed -> {
                        logger.w {
                            "[PhaseSparkLibrary] failed to parse $path: ${parsed.error}"
                        }
                    }
                }
            }
            return DefaultPhaseSparkLibrary(
                phaseSparks = phaseSparks.toList(),
                roleSparks = roleSparks.toMap(),
                languageSparks = languageSparks.toMap(),
                projectSparks = projectSparks.toMap(),
            )
        }

        /**
         * Builds a library from already-parsed phase sources (useful for tests
         * that exercise the phase-spark surface).
         */
        internal fun fromSources(sources: List<DeclarativePhaseSparkSource>): DefaultPhaseSparkLibrary =
            DefaultPhaseSparkLibrary(phaseSparks = sources.map { it.toPhaseSpark() })

        @OptIn(ExperimentalResourceApi::class)
        private suspend fun readResource(path: String): String? =
            runCatching { Res.readBytes(path).decodeToString() }.getOrElse { composeError ->
                val fallbackPath = "composeResources/link.socket.ampere.resources/$path"
                val fallback = loadBundledSparkFallback(
                    resourcePath = fallbackPath,
                    fallbackPath = path,
                )
                if (fallback == null) {
                    logger.w(composeError) { "[PhaseSparkLibrary] failed to read resource $path" }
                }
                fallback
            }

        private fun extractKeywords(text: String): Set<String> {
            if (text.isBlank()) return emptySet()
            return text.lowercase()
                .split(*KEYWORD_SPLITTERS)
                .asSequence()
                .map { it.trim() }
                .filter { it.length >= 3 }
                .toSet()
        }
    }
}

/**
 * Platform-specific fallback for reading a bundled spark resource. Used when
 * `Res.readBytes` fails (typically in unit tests where Compose's resource
 * graph is not initialised).
 */
internal expect suspend fun loadBundledSparkFallback(
    resourcePath: String,
    fallbackPath: String,
): String?
