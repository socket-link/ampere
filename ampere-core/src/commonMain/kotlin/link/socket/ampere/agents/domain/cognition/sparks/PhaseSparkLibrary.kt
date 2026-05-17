package link.socket.ampere.agents.domain.cognition.sparks

/**
 * Context handed to [PhaseSparkLibrary.selectFor] to choose declarative sparks.
 *
 * - [phase] filters sparks whose source declared this phase.
 * - [text] is matched against `whenToUse` and `name` for keyword relevance.
 * - [tags] (when non-empty) requires intersection with the spark's tags.
 * - [agentRole] is reserved for role-aware selection; unused today.
 */
internal data class SparkSelectionContext(
    val phase: CognitivePhase,
    val text: String,
    val tags: Set<String> = emptySet(),
    val agentRole: String? = null,
)

/**
 * Internal phase-spark surface consumed synchronously by [PhaseSparkManager]
 * at phase entry, hence the non-suspend methods. Implementations populate
 * the catalog asynchronously (e.g. `DefaultPhaseSparkLibrary.load`) and
 * hand a fully-resolved library to the caller.
 *
 * Extends [SparkRegistry] so the same instance serves the public role-spark
 * lookup used by the [link.socket.ampere.agents.definition.SparkBasedAgent]
 * factories — one catalog, two access surfaces split by visibility.
 *
 * Implementations should:
 * - Return deterministic results from [selectFor] (stable order across runs)
 * - Never throw from these accessors; surface failures via empty results
 */
internal interface PhaseSparkLibrary : SparkRegistry {

    /** All phase sparks the library could expose, regardless of selection. */
    fun all(): List<PhaseSpark>

    /** Returns the phase spark with [id], or null if none. */
    fun byId(id: PhaseSparkId): PhaseSpark?

    /** Returns phase sparks matching [context], ordered deterministically. */
    fun selectFor(context: SparkSelectionContext): List<PhaseSpark>
}
