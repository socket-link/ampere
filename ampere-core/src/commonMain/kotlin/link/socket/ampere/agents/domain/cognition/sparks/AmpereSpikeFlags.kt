package link.socket.ampere.agents.domain.cognition.sparks

/**
 * Feature flags for in-progress cognition spikes.
 *
 * Flags default to `false`. Tests that flip a flag must restore it via
 * `try { ... } finally { AmpereSpikeFlags.<flag> = false }`.
 */
internal object AmpereSpikeFlags {
    /**
     * When `true`, [PhaseSparkManager] consults the configured
     * [PhaseSparkLibrary] and applies matching declarative sparks alongside
     * the built-in phase spark.
     */
    var declarativeSparksEnabled: Boolean = false
}
