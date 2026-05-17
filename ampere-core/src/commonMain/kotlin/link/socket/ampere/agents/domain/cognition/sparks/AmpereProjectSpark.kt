package link.socket.ampere.agents.domain.cognition.sparks

/**
 * Canonical project spark for agents working on the AMPERE codebase.
 *
 * The project description and conventions live in `project-ampere.spark.md`.
 * Repository-root resolution is handled by the declarative fixture via
 * `${env:AMPERE_ROOT:-${env:PWD:-.}}`, so runtime behavior stays equivalent
 * to the previous Kotlin implementation without duplicating the project text
 * in code.
 */
object AmpereProjectSpark {

    /**
     * The ProjectSpark instance for AMPERE.
     */
    val spark: ProjectSpark by lazy {
        DefaultSparkCatalog.requireProjectSpark(ProjectSparkIds.AMPERE)
    }

    /**
     * Creates a ProjectSpark for AMPERE with a specific repository root.
     *
     * Use this when the repository root needs to be explicitly specified
     * (e.g., in tests or when working in non-standard directory structures).
     */
    fun withRepositoryRoot(repositoryRoot: String): ProjectSpark =
        spark.copy(repositoryRoot = repositoryRoot)
}
