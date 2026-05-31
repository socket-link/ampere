package link.socket.ampere.phosphor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MilestoneCategory
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.palette.AtmospherePresets

class DefaultPropelStrategyTest {

    @Test
    fun `canonical phase mapping matches spec`() {
        assertEquals(AtmospherePresets.LISTENING, DefaultPropelStrategy.atmosphereFor(CognitivePhase.PERCEIVE))
        assertEquals(AtmospherePresets.THINKING, DefaultPropelStrategy.atmosphereFor(CognitivePhase.RECALL))
        assertEquals(AtmospherePresets.LISTENING, DefaultPropelStrategy.atmosphereFor(CognitivePhase.OBSERVE))
        assertEquals(AtmospherePresets.THINKING, DefaultPropelStrategy.atmosphereFor(CognitivePhase.PLAN))
        assertEquals(AtmospherePresets.READY, DefaultPropelStrategy.atmosphereFor(CognitivePhase.EXECUTE))
        assertEquals(AtmospherePresets.THINKING, DefaultPropelStrategy.atmosphereFor(CognitivePhase.LEARN))
    }

    @Test
    fun `consecutive phases never share an atmosphere`() {
        val canonicalCycle = listOf(
            CognitivePhase.PERCEIVE,
            CognitivePhase.RECALL,
            CognitivePhase.OBSERVE,
            CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        )
        canonicalCycle.zipWithNext().forEach { (a, b) ->
            assertNotEquals(
                DefaultPropelStrategy.atmosphereFor(a),
                DefaultPropelStrategy.atmosphereFor(b),
                "Phases $a and $b should produce different atmospheres",
            )
        }
    }

    @Test
    fun `default strategy never returns UNCERTAIN for any PROPEL phase`() {
        CognitivePhase.entries.forEach { phase ->
            assertNotEquals(
                AtmospherePresets.UNCERTAIN,
                DefaultPropelStrategy.atmosphereFor(phase),
                "UNCERTAIN must be reserved for escalation, but was returned for $phase",
            )
        }
    }

    @Test
    fun `glyph mapping covers the five canonical signals`() {
        assertEquals(LumosGlyph.CHECK, DefaultPropelStrategy.glyphFor(taskCompletedEvent()))
        assertEquals(LumosGlyph.EXCLAIM, DefaultPropelStrategy.glyphFor(taskFailedEvent()))
        assertEquals(LumosGlyph.QUESTION, DefaultPropelStrategy.glyphFor(escalationFiredEvent()))
        assertEquals(LumosGlyph.STAR, DefaultPropelStrategy.glyphFor(milestoneEvent()))
        assertEquals(LumosGlyph.EXCLAIM, DefaultPropelStrategy.glyphFor(toolFailedEvent()))
    }

    @Test
    fun `successful tool execution does not produce a glyph`() {
        assertNull(DefaultPropelStrategy.glyphFor(toolCompletedEvent(success = true)))
    }

    private fun taskCompletedEvent(): TaskEvent.TaskCompleted = TaskEvent.TaskCompleted(
        eventId = "evt-task-completed",
        taskId = "task-1",
        eventSource = EventSource.Agent("agent-A"),
        timestamp = Instant.fromEpochSeconds(0),
        summary = "done",
    )

    private fun taskFailedEvent(): TaskEvent.TaskFailed = TaskEvent.TaskFailed(
        eventId = "evt-task-failed",
        taskId = "task-1",
        eventSource = EventSource.Agent("agent-A"),
        timestamp = Instant.fromEpochSeconds(0),
        reason = "boom",
    )

    private fun escalationFiredEvent(): CognitiveEvent.EscalationFired = CognitiveEvent.EscalationFired(
        eventId = "evt-escalation",
        timestamp = Instant.fromEpochSeconds(0),
        eventSource = EventSource.Agent("agent-A"),
        agentId = "agent-A",
        uncertaintyValue = 0.91,
        threshold = 0.85,
        prompt = "should I continue?",
        cognitivePhase = CognitivePhase.PLAN,
    )

    private fun milestoneEvent(): MemoryEvent.MilestoneReached = MemoryEvent.MilestoneReached(
        eventId = "evt-milestone",
        timestamp = Instant.fromEpochSeconds(0),
        eventSource = EventSource.Agent("agent-A"),
        agentId = "agent-A",
        milestoneId = "ms-1",
        description = "first success",
        knowledgeId = null,
        taskId = null,
        runId = null,
        category = MilestoneCategory.FIRST_SUCCESS,
    )

    private fun toolFailedEvent(): ToolEvent.ToolExecutionCompleted =
        toolCompletedEvent(success = false)

    private fun toolCompletedEvent(success: Boolean): ToolEvent.ToolExecutionCompleted =
        ToolEvent.ToolExecutionCompleted(
            eventId = "evt-tool-$success",
            timestamp = Instant.fromEpochSeconds(0),
            eventSource = EventSource.Agent("agent-A"),
            urgency = Urgency.LOW,
            invocationId = "inv-1",
            toolId = "tool-1",
            toolName = "test-tool",
            success = success,
            durationMs = 100L,
        )

    @Suppress("unused")
    private fun ignoredEvent(): Event = Event.QuestionRaised(
        eventId = "evt-question",
        urgency = Urgency.MEDIUM,
        timestamp = Instant.fromEpochSeconds(0),
        eventSource = EventSource.Agent("agent-A"),
        questionText = "?",
        context = "",
    )
}
