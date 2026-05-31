package link.socket.ampere.phosphor

import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.signal.AtmosphereState

/**
 * Pluggable translation from AMPERE cognitive events to Phosphor Lumos targets.
 *
 * Implementations are pure functions of their input. They never observe runtime state,
 * mutate the bridge, or accumulate context across calls. Consumers can swap a strategy
 * to express domain-specific atmosphere or glyph opinions without rebuilding the bridge.
 *
 * `UNCERTAIN` is reserved for escalation by design — strategies must not return it from
 * [atmosphereFor]. The bridge applies `UNCERTAIN` directly when an
 * [link.socket.ampere.agents.domain.event.CognitiveEvent.EscalationFired] arrives.
 */
interface PropelToAtmosphereStrategy {

    fun atmosphereFor(phase: CognitivePhase): AtmosphereState

    fun glyphFor(event: Event): LumosGlyph?
}
