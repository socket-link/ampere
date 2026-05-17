package link.socket.ampere.agents.domain.cognition.sparks

import co.touchlab.kermit.Logger
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
)

/**
 * In-memory [PhaseSparkLibrary] backed by a pre-parsed set of declarative sparks.
 *
 * Construction takes a fully-resolved list of sparks so the runtime accessors
 * (`all`, `byId`, `selectFor`) are non-suspend. Use [DefaultPhaseSparkLibrary.load]
 * to read and parse the bundled `.spark.md` resources asynchronously.
 *
 * The loader mirrors the resource-loading shape of
 * [link.socket.ampere.domain.ai.pricing.BundledProviderPricingCatalog]: it tries
 * `Res.readBytes` first, then falls back to a platform-specific classpath
 * lookup so JVM unit tests work without the Compose resource graph. Parse
 * failures are logged via [logWith] and the offending spark is skipped — loading
 * never throws.
 */
internal class DefaultPhaseSparkLibrary internal constructor(
    private val sparks: List<PhaseSpark>,
) : PhaseSparkLibrary {

    override fun all(): List<PhaseSpark> = sparks

    override fun byId(id: PhaseSparkId): PhaseSpark? =
        sparks.firstOrNull { spark -> spark is DeclarativePhaseSpark && spark.sparkId == id }

    override fun selectFor(context: SparkSelectionContext): List<PhaseSpark> {
        val phaseMatches = sparks.filterIsInstance<DeclarativePhaseSpark>()
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
            val seenIds = mutableSetOf<PhaseSparkId>()
            val sparks = mutableListOf<PhaseSpark>()
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
                            sparks += source.toPhaseSpark()
                        }
                    }
                    is SparkParseResult.Failed -> {
                        logger.w {
                            "[PhaseSparkLibrary] failed to parse $path: ${parsed.error}"
                        }
                    }
                }
            }
            return DefaultPhaseSparkLibrary(sparks.toList())
        }

        /**
         * Builds a library from already-parsed sources (useful for tests).
         */
        internal fun fromSources(sources: List<DeclarativePhaseSparkSource>): DefaultPhaseSparkLibrary =
            DefaultPhaseSparkLibrary(sources.map { it.toPhaseSpark() })

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
