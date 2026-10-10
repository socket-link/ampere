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
 * [enabled] is the master switch: with it off the manager does nothing at all,
 * which is the default for every caller that does not opt in. With it on, the
 * two behaviours it used to fuse are independently addressable (AMPR-387):
 * [publishBrackets] makes the phase boundaries observable, and
 * [injectPhaseSparks] adds the phase guidance to the system prompt. Both
 * default to on, so turning [enabled] on alone reproduces the pre-AMPR-387
 * behaviour; setting `injectPhaseSparks = false` brackets a run silently,
 * leaving the agent's prompt exactly as it would be with phase sparks off.
 *
 * The `AMPERE_PHASE_SPARKS` environment variable is a developer override that
 * forces all three on regardless of what is configured here.
 *
 * @property enabled Enables phase handling when true; nothing happens when false.
 * @property phases The phases that should receive PhaseSparks when enabled.
 * @property publishBrackets Publishes `CognitivePhaseEvent.PhaseEntered` /
 *   `PhaseExited` at phase boundaries. Requires a door on the agent — with no
 *   event api wired there is nothing to publish through.
 * @property injectPhaseSparks Pushes `PhaseSpark` prompt content (and any
 *   declarative phase sparks selected from the agent's library) onto the spark
 *   stack for the duration of the phase. Off, the agent's system prompt is
 *   byte-for-byte what it would be with [enabled] off — including the per-phase
 *   sections its role and language sparks would otherwise contribute.
 */
@Serializable
data class PhaseSparkConfig(
    val enabled: Boolean = false,
    val phases: Set<CognitivePhase> = enumValues<CognitivePhase>().toSet(),
    val publishBrackets: Boolean = true,
    val injectPhaseSparks: Boolean = true,
)
