package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.routing.RoutingDecision
import link.socket.ampere.agents.events.utils.generateUUID

class RoutingEventTest {

    @Test
    fun `RouteSelected has correct event type`() {
        val decision = RoutingDecision(
            providerName = "Anthropic",
            modelName = "claude-sonnet-4-0",
            matchedRule = "phase:EXECUTE",
        )
        val event = RoutingEvent.RouteSelected(
            eventId = generateUUID("routing"),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("test-agent"),
            agentId = "test-agent",
            phase = CognitivePhase.EXECUTE,
            decision = decision,
        )

        assertEquals("RouteSelected", event.eventType)
        assertEquals("test-agent", event.agentId)
        assertEquals(CognitivePhase.EXECUTE, event.phase)
        assertEquals("Anthropic", event.decision.providerName)
        assertEquals("phase:EXECUTE", event.decision.matchedRule)
    }

    @Test
    fun `RouteSelected summary contains provider and model`() {
        val decision = RoutingDecision(
            providerName = "Google",
            modelName = "gemini-2.5-flash",
            matchedRule = "phase:PERCEIVE",
        )
        val event = RoutingEvent.RouteSelected(
            eventId = generateUUID("routing"),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-1"),
            agentId = "agent-1",
            phase = CognitivePhase.PERCEIVE,
            decision = decision,
        )

        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { when (it) { is EventSource.Agent -> it.agentId; else -> "Unknown" } },
        )
        assertTrue(summary.contains("Google/gemini-2.5-flash"))
        assertTrue(summary.contains("PERCEIVE"))
        assertTrue(summary.contains("phase:PERCEIVE"))
    }

    @Test
    fun `RouteSelected summary includes FALLBACK marker when applicable`() {
        val decision = RoutingDecision(
            providerName = "OpenAI",
            modelName = "gpt-4.1",
            matchedRule = "default",
            isFallback = true,
        )
        val event = RoutingEvent.RouteSelected(
            eventId = generateUUID("routing"),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Human,
            agentId = null,
            phase = null,
            decision = decision,
        )

        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { "system" },
        )
        assertTrue(summary.contains("[FALLBACK]"))
    }

    @Test
    fun `RouteFallback has correct event type and urgency`() {
        val fallbackDecision = RoutingDecision(
            providerName = "OpenAI",
            modelName = "gpt-4.1",
            matchedRule = "default",
            isFallback = true,
        )
        val event = RoutingEvent.RouteFallback(
            eventId = generateUUID("routing"),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-1"),
            agentId = "agent-1",
            phase = CognitivePhase.EXECUTE,
            failedProvider = "Anthropic",
            failedModel = "claude-sonnet-4-0",
            fallbackDecision = fallbackDecision,
            failureReason = "API key invalid",
        )

        assertEquals("RouteFallback", event.eventType)
        assertEquals(Urgency.MEDIUM, event.urgency)
        assertEquals("Anthropic", event.failedProvider)
        assertEquals("API key invalid", event.failureReason)
    }

    @Test
    fun `RouteFallback summary contains failed and fallback providers`() {
        val fallbackDecision = RoutingDecision(
            providerName = "Google",
            modelName = "gemini-2.5-flash",
            matchedRule = "default",
            isFallback = true,
        )
        val event = RoutingEvent.RouteFallback(
            eventId = generateUUID("routing"),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-1"),
            agentId = "agent-1",
            phase = null,
            failedProvider = "Anthropic",
            failedModel = "claude-sonnet-4-0",
            fallbackDecision = fallbackDecision,
            failureReason = "rate limited",
        )

        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { when (it) { is EventSource.Agent -> it.agentId; else -> "Unknown" } },
        )
        assertTrue(summary.contains("Anthropic/claude-sonnet-4-0"))
        assertTrue(summary.contains("Google/gemini-2.5-flash"))
        assertTrue(summary.contains("rate limited"))
    }

    @Test
    fun `exhaustive when over sealed interface compiles`() {
        fun handle(event: RoutingEvent): String = when (event) {
            is RoutingEvent.RouteSelected -> "selected"
            is RoutingEvent.RouteFallback -> "fallback"
        }

        val decision = RoutingDecision("P", "M", "rule")
        val event = RoutingEvent.RouteSelected(
            eventId = "id",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Human,
            agentId = null,
            phase = null,
            decision = decision,
        )
        assertEquals("selected", handle(event))
    }
}
