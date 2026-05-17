package link.socket.ampere.agents.domain.cognition.sparks

import link.socket.ampere.agents.domain.cognition.Spark

/**
 * Public-facing registry of declarative sparks bundled with the runtime.
 *
 * External code paths — most notably the [link.socket.ampere.agents.definition.SparkBasedAgent.Code]
 * and [link.socket.ampere.agents.definition.SparkBasedAgent.Quality] factories
 * — resolve their role spark through this surface, by canonical id, so the
 * factory call site doesn't need to know about the internal phase-spark
 * selection machinery exposed by [PhaseSparkLibrary].
 *
 * Implementations are expected to be deterministic across calls and never
 * throw from accessors (surface "not found" as null).
 */
interface SparkRegistry {

    /**
     * Returns the role spark loaded from a `"role"` JSON fixture with the
     * given canonical [id], or null if no such fixture is bundled. The id
     * matches the `"id"` field of the fixture's frontmatter (e.g. `"code"`
     * for `role-code.spark.md`) — not the human-readable `Role:Code` name.
     */
    fun roleSparkById(id: String): Spark?
}
