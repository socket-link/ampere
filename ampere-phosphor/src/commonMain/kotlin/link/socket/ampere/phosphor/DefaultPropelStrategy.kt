package link.socket.ampere.phosphor

import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState

/**
 * Canonical six-phase PROPEL to Lumos atmosphere mapping.
 *
 * The alternation LISTENING / THINKING / LISTENING / THINKING / READY / THINKING
 * is intentional: no two consecutive phases share an atmosphere, which gives the
 * orb visible progression through the cycle.
 */
object DefaultPropelStrategy : PropelToAtmosphereStrategy {

    override fun atmosphereFor(phase: CognitivePhase): AtmosphereState = when (phase) {
        CognitivePhase.PERCEIVE -> AtmospherePresets.LISTENING
        CognitivePhase.RECALL -> AtmospherePresets.THINKING
        CognitivePhase.OBSERVE -> AtmospherePresets.LISTENING
        CognitivePhase.PLAN -> AtmospherePresets.THINKING
        CognitivePhase.EXECUTE -> AtmospherePresets.READY
        CognitivePhase.LEARN -> AtmospherePresets.THINKING
    }

    override fun glyphFor(event: Event): LumosGlyph? = when (event) {
        is TaskEvent.TaskCompleted -> LumosGlyph.CHECK
        is TaskEvent.TaskFailed -> LumosGlyph.EXCLAIM
        is CognitiveEvent.EscalationFired -> LumosGlyph.QUESTION
        is MemoryEvent.MilestoneReached -> LumosGlyph.STAR
        is ToolEvent.ToolExecutionCompleted -> if (!event.success) LumosGlyph.EXCLAIM else null
        else -> null
    }
}
