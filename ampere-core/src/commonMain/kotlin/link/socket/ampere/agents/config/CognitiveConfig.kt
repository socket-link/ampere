package link.socket.ampere.agents.config

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

/**
 * Configuration for cognitive-loop behavior and related prompt shaping.
 */
@Serializable
data class CognitiveConfig(
    val phaseSparks: PhaseSparkConfig = PhaseSparkConfig(),
)

/**
 * Configuration for optional PhaseSparks during the PROPEL cycle.
 *
 * @property enabled Enables phase-specific Sparks when true.
 * @property phases The phases that should receive PhaseSparks when enabled.
 */
@Serializable
data class PhaseSparkConfig(
    val enabled: Boolean = false,
    val phases: Set<CognitivePhase> = enumValues<CognitivePhase>().toSet(),
)
