package link.socket.ampere.domain.arc

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung

@Serializable
data class ArcAgentConfig(
    val role: String,
    val sparks: List<String> = emptyList(),
    /**
     * Per-step capability-rung floor (AMPR-232).
     *
     * Declares the minimum rung the model backing this Arc step must clear.
     * Propagated by the Arc agent spawner (`ChargePhase`) into the spawned
     * agent's `AgentDefinition.minimumRung`, where
     * [AgentLLMService][link.socket.ampere.agents.domain.reasoning.AgentLLMService]
     * merges it into the `RoutingContext` and the relay refuses to route below it.
     *
     * Composes with [ArcConfig.minimumRung] as the *stricter* of the two — see
     * [minimumRungFor]. Floors only ever raise: null declares no floor and leaves
     * the step on its configured model.
     *
     * A floor below [CapabilityRung.ONE] is refused at construction, so a manifest
     * that declares one fails where it is decoded rather than when the step runs
     * (AMPR-459): the step runs the PROPEL loop, and rung ZERO serves display text
     * only (AMPR-372). See [requireArcFloor].
     */
    val minimumRung: CapabilityRung? = null,
) {
    init {
        requireArcFloor(minimumRung) { "Arc step '$role'" }
    }
}
