package link.socket.ampere.agents.domain.cognition.sparks

import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.util.runBlockingCompat

/**
 * Shared synchronous access to the bundled declarative spark registry for
 * legacy factory surfaces that cannot become suspend without a larger API
 * migration.
 */
internal object DefaultSparkCatalog {
    val registry: SparkRegistry by lazy {
        runBlockingCompat { DefaultPhaseSparkLibrary.load() }
    }

    fun requireRoleSpark(id: String): Spark =
        registry.roleSparkById(id)
            ?: error(
                "Missing bundled role spark '$id'. Use DefaultPhaseSparkLibrary.load() " +
                    "with files/sparks/role-$id.spark.md included.",
            )

    fun requireLanguageSpark(id: String): Spark =
        registry.languageSparkById(id)
            ?: error(
                "Missing bundled language spark '$id'. Use DefaultPhaseSparkLibrary.load() " +
                    "with files/sparks/language-$id.spark.md included.",
            )

    fun requireProjectSpark(id: String): ProjectSpark =
        registry.projectSparkById(id)
            ?: error(
                "Missing bundled project spark '$id'. Use DefaultPhaseSparkLibrary.load() " +
                    "with files/sparks/project-$id.spark.md included.",
            )
}
