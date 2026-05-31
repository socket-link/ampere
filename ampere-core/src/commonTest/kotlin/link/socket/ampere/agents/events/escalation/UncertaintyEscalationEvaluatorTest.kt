package link.socket.ampere.agents.events.escalation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription

@OptIn(ExperimentalCoroutinesApi::class)
class UncertaintyEscalationEvaluatorTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-05-31T12:00:00Z")
    }

    @Test
    fun `uncertainty at threshold publishes EscalationFired with payload through bus`() = runTest {
        val bus = EventSerialBus(scope = this)
        val received = mutableListOf<CognitiveEvent.EscalationFired>()
        val evaluator = evaluatorPublishingTo(bus)

        bus.subscribe<CognitiveEvent.EscalationFired, EventSubscription.ByEventClassType>(
            agentId = "subscriber",
            eventType = CognitiveEvent.EscalationFired.EVENT_TYPE,
        ) { event, _ ->
            received += event
        }

        val fired = evaluator.evaluate(
            uncertaintyValue = 0.7,
            threshold = 0.7,
            prompt = "Choose between migration plans",
            cognitivePhase = CognitivePhase.PLAN,
        )
        advanceUntilIdle()

        assertTrue(fired)
        assertEquals(1, received.size)
        val event = received.single()
        assertEquals("agent-threshold", event.agentId)
        assertEquals(0.7, event.uncertaintyValue)
        assertEquals(0.7, event.threshold)
        assertEquals("Choose between migration plans", event.prompt)
        assertEquals(CognitivePhase.PLAN, event.cognitivePhase)
        assertEquals(EventSource.Agent("agent-threshold"), event.eventSource)
        assertEquals(Urgency.HIGH, event.urgency)
        assertEquals(fixedClock.now(), event.timestamp)
    }

    @Test
    fun `uncertainty below threshold does not publish EscalationFired`() = runTest {
        val bus = EventSerialBus(scope = this)
        val received = mutableListOf<CognitiveEvent.EscalationFired>()
        val evaluator = evaluatorPublishingTo(bus)

        bus.subscribe<CognitiveEvent.EscalationFired, EventSubscription.ByEventClassType>(
            agentId = "subscriber",
            eventType = CognitiveEvent.EscalationFired.EVENT_TYPE,
        ) { event, _ ->
            received += event
        }

        val fired = evaluator.evaluate(
            uncertaintyValue = 0.69,
            threshold = 0.7,
            prompt = "Choose between migration plans",
        )
        advanceUntilIdle()

        assertFalse(fired)
        assertEquals(emptyList(), received)
    }

    @Test
    fun `multiple rapid threshold fires publish multiple events`() = runTest {
        val bus = EventSerialBus(scope = this)
        val received = mutableListOf<CognitiveEvent.EscalationFired>()
        val evaluator = evaluatorPublishingTo(bus)

        bus.subscribe<CognitiveEvent.EscalationFired, EventSubscription.ByEventClassType>(
            agentId = "subscriber",
            eventType = CognitiveEvent.EscalationFired.EVENT_TYPE,
        ) { event, _ ->
            received += event
        }

        evaluator.evaluate(uncertaintyValue = 0.8, threshold = 0.7, prompt = "First prompt")
        evaluator.evaluate(uncertaintyValue = 0.9, threshold = 0.7, prompt = "Second prompt")
        advanceUntilIdle()

        assertEquals(2, received.size)
        assertEquals(listOf("First prompt", "Second prompt"), received.map { it.prompt })
    }

    @Test
    fun `uncertainty values must be normalized`() = runTest {
        val evaluator = evaluatorPublishingTo(EventSerialBus(scope = this))

        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(uncertaintyValue = 1.1, threshold = 0.7, prompt = "invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(uncertaintyValue = 0.5, threshold = -0.1, prompt = "invalid")
        }
    }

    private fun evaluatorPublishingTo(bus: EventSerialBus): UncertaintyEscalationEvaluator =
        UncertaintyEscalationEvaluator(
            agentId = "agent-threshold",
            publishEscalationFired = { event -> bus.publish(event) },
            clock = fixedClock,
        )
}
