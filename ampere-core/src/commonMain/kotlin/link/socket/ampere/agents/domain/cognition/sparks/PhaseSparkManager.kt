package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.util.getEnvironmentVariable

/**
 * Manages the lifecycle of PhaseSparks during the cognitive cycle.
 *
 * **Ticket #230**: PhaseSparkManager provides automatic phase transition handling
 * for agents that opt in to phase-specific cognitive context. When enabled, it:
 * - Applies the appropriate PhaseSpark at phase entry
 * - Removes the previous PhaseSpark before applying the new one
 * - Ensures cleanup even during failures or cancellations
 *
 * Phase Sparks are disabled by default to maintain backward compatibility.
 * Enable via [PhaseSparkConfig], the `enabled` property, or environment variable `AMPERE_PHASE_SPARKS`.
 *
 * Usage:
 * ```kotlin
 * val manager = PhaseSparkManager(agent, enabled = true)
 *
 * // At phase boundaries:
 * manager.enterPhase(CognitivePhase.PERCEIVE)
 * // ... do perception work ...
 * manager.enterPhase(CognitivePhase.PLAN)
 * // ... do planning work ...
 *
 * // Or use the wrapper function:
 * manager.withPhase(CognitivePhase.EXECUTE) {
 *     // ... do execution work ...
 * }
 *
 * // Cleanup at end of cognitive cycle:
 * manager.cleanup()
 * ```
 *
 * @param agent The agent whose Spark stack to manage
 * @param enabled Whether phase Sparks are active (default: false)
 * @param activePhases Which phases should receive PhaseSparks when enabled
 */
class PhaseSparkManager<S : AgentState>(
    private val agent: AutonomousAgent<S>,
    val enabled: Boolean = isPhaseSparkEnabled(),
    private val activePhases: Set<CognitivePhase> = DEFAULT_PHASES,
) {
    /**
     * The currently active PhaseSpark, or null if none is active.
     */
    private var currentPhase: CognitivePhase? = null

    /**
     * Tracks whether we have pushed a PhaseSpark that needs to be removed.
     */
    private var hasActivePhase: Boolean = false

    /**
     * Transitions to a new cognitive phase.
     *
     * If a previous PhaseSpark is active, it will be removed before
     * the new one is applied. No-op if phase Sparks are disabled.
     *
     * @param phase The phase to enter
     */
    fun enterPhase(phase: CognitivePhase) {
        if (!enabled) return

        // Remove previous phase Spark if present
        if (hasActivePhase && currentPhase != phase) {
            removeActivePhaseSpark()
        }

        if (!isPhaseEnabled(phase)) {
            return
        }

        // Apply new phase Spark
        if (currentPhase != phase || !hasActivePhase) {
            val phaseSpark = PhaseSpark.forPhase(phase)
            agent.spark<AutonomousAgent<S>>(phaseSpark)
            currentPhase = phase
            hasActivePhase = true
        }
    }

    /**
     * Executes a block with a specific phase Spark applied.
     *
     * The phase Spark is applied before the block executes and removed
     * after completion (success, failure, or cancellation).
     *
     * @param phase The phase to use during execution
     * @param block The code to execute within this phase
     * @return The result of the block
     */
    suspend fun <R> withPhase(phase: CognitivePhase, block: suspend () -> R): R {
        if (!enabled) return block()

        val previousPhase = currentPhase
        val hadActivePhase = hasActivePhase
        enterPhase(phase)

        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                // Remove current phase Spark
                removeActivePhaseSpark()

                // Restore previous phase if there was one
                if (previousPhase != null && hadActivePhase && isPhaseEnabled(previousPhase)) {
                    val previousSpark = PhaseSpark.forPhase(previousPhase)
                    agent.spark<AutonomousAgent<S>>(previousSpark)
                    currentPhase = previousPhase
                    hasActivePhase = true
                }
            }
        }
    }

    /**
     * Exits the current phase without entering a new one.
     *
     * Call this at the end of a cognitive cycle to ensure no
     * phase Spark remains on the stack.
     */
    fun cleanup() {
        if (!enabled) return

        removeActivePhaseSpark()
    }

    /**
     * Gets the current active phase, if any.
     */
    fun getCurrentPhase(): CognitivePhase? = if (enabled && hasActivePhase) currentPhase else null

    /**
     * Checks if the manager currently has an active phase Spark.
     */
    fun isPhaseActive(): Boolean = enabled && hasActivePhase

    private fun isPhaseEnabled(phase: CognitivePhase): Boolean = activePhases.contains(phase)

    private fun removeActivePhaseSpark() {
        if (hasActivePhase) {
            agent.unspark()
            hasActivePhase = false
            currentPhase = null
        }
    }

    companion object {
        private val DEFAULT_PHASES: Set<CognitivePhase> = enumValues<CognitivePhase>().toSet()

        /**
         * Checks if phase Sparks should be enabled.
         *
         * Resolution order:
         * 1. `AMPERE_PHASE_SPARKS` environment variable ("true" to enable)
         * 2. Default: disabled (false)
         */
        fun isPhaseSparkEnabled(): Boolean {
            return try {
                getEnvironmentVariable("AMPERE_PHASE_SPARKS")
                    ?.equals("true", ignoreCase = true)
                    ?: false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Creates a PhaseSparkManager for the given agent.
         *
         * @param agent The agent to manage
         * @param phaseConfig Configuration for phase Sparks (optional)
         * @return A new PhaseSparkManager
         */
        fun <S : AgentState> create(
            agent: AutonomousAgent<S>,
            phaseConfig: PhaseSparkConfig? = null,
        ): PhaseSparkManager<S> {
            val enabledFromConfig = phaseConfig?.enabled ?: false
            val enabled = enabledFromConfig || isPhaseSparkEnabled()
            val phases = phaseConfig?.phases ?: DEFAULT_PHASES
            return PhaseSparkManager(
                agent = agent,
                enabled = enabled,
                activePhases = phases,
            )
        }
    }
}
