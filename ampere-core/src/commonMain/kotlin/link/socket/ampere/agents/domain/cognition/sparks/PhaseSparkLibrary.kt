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
 * Read-only catalog of declarative [PhaseSpark]s available for selection at
 * phase entry.
 *
 * The interface is intentionally non-suspend so it can be consulted from
 * synchronous code paths such as [PhaseSparkManager.enterPhase]. Implementations
 * that need async I/O to populate their catalog should expose a suspend
 * factory (e.g. `DefaultPhaseSparkLibrary.load(...)`) that materialises the
 * sparks once and hands a fully-resolved library back to the caller.
 *
 * Implementations should:
 * - Return deterministic results from [selectFor] (stable order across runs)
 * - Never throw from these accessors; surface failures via empty results
 */
internal interface PhaseSparkLibrary {

    /** All declarative sparks the library could expose, regardless of selection. */
    fun all(): List<PhaseSpark>

    /** Returns the spark with [id] for any phase, or null if none. */
    fun byId(id: PhaseSparkId): PhaseSpark?

    /** Returns declarative sparks matching [context], ordered deterministically. */
    fun selectFor(context: SparkSelectionContext): List<PhaseSpark>
}
