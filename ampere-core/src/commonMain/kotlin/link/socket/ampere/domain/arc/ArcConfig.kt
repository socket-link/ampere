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
     *
     * Refused below [CapabilityRung.ONE] at construction, as a step's floor is
     * (AMPR-459) — see [requireArcFloor].
     */
    val minimumRung: CapabilityRung? = null,
    /**
     * What a runtime for this Arc does with a run requested while another is in flight
     * (AMPR-284). [ArcConcurrencyPolicy.REJECT] is the only implemented policy and the default.
     */
    val concurrency: ArcConcurrencyPolicy = ArcConcurrencyPolicy.REJECT,
) {
    init {
        requireArcFloor(minimumRung) { "Arc '$name'" }
    }
}

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

/**
 * Refuses a floor below [CapabilityRung.ONE] on an Arc or one of its steps (AMPR-459).
 *
 * Every Arc step is an agent that runs the PROPEL loop, and rung ZERO serves display
 * text only (AMPR-372). A `ZERO` floor made every phase of a step eligible for the
 * device, PLAN included, and sent what the device could not take cheapest-capable over
 * the whole catalog instead of to the step's own model. Refused on the manifest types
 * themselves, so an Arc decoded from YAML or JSON fails where it is written, not when it
 * runs. A missing floor is legal: it keeps the step on its configured model.
 *
 * @param declaredBy Names the Arc or the step for the message; only read on a refusal.
 */
internal fun requireArcFloor(floor: CapabilityRung?, declaredBy: () -> String) {
    require(floor == null || floor >= CapabilityRung.ONE) {
        "${declaredBy()} declares minimumRung ${floor?.ordinal}, below ONE. Rung ZERO serves display " +
            "text only, and every Arc step runs the PROPEL loop, so an Arc floor is ONE or above " +
            "(AMPR-372). Leave it unset to keep each step on its configured model. See the " +
            "CognitiveRelay concept, docs/concepts/cognitive-relay.md."
    }
}
