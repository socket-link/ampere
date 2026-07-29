package link.socket.ampere.domain.arc

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung

@Serializable
data class ArcConfig(
    val name: String,
    val description: String? = null,
    val agents: List<ArcAgentConfig>,
    val orchestration: OrchestrationConfig = OrchestrationConfig(),
    /**
     * Arc-wide capability-rung floor (AMPR-232).
     *
     * Applies to every step that does not declare its own
     * [ArcAgentConfig.minimumRung]. A step *may* raise this floor but never
     * lowers it — see [minimumRungFor]. Null leaves the Arc unconstrained.
     */
    val minimumRung: CapabilityRung? = null,
)

/**
 * The capability-rung floor in force for [agent] within this Arc (AMPR-232).
 *
 * Floors compose as the stricter of the Arc-wide [ArcConfig.minimumRung] and the
 * per-step [ArcAgentConfig.minimumRung], mirroring how a call-site floor composes
 * with an agent's declared floor in
 * [AgentLLMService][link.socket.ampere.agents.domain.reasoning.AgentLLMService]
 * (AMPR-229). Declaring a floor can only ever raise the bar: a step asking for
 * `TWO` inside an Arc declaring `THREE` still routes against `THREE`, because the
 * alternative is a silent downgrade of a floor someone deliberately set.
 *
 * Returns null when neither declares a floor, which leaves the step unconstrained.
 */
fun ArcConfig.minimumRungFor(agent: ArcAgentConfig): CapabilityRung? {
    val arcFloor = minimumRung
    val stepFloor = agent.minimumRung
    return when {
        arcFloor == null -> stepFloor
        stepFloor == null -> arcFloor
        else -> maxOf(arcFloor, stepFloor)
    }
}
