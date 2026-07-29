package link.socket.ampere.agents.events.bus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.subscription.EventSubscription

/**
 * Per-subscription teardown on the bus (AMPR-243).
 *
 * Before this, `unsubscribe` took an event type and removed *every* handler registered for it,
 * so one consumer letting go silenced all the others. That made the bus unsafe to hand a second
 * Flow consumer — which the Swift Arc bridge is, and the first one that cancels routinely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventSerialBusSubscriptionTest {

    private fun taskEvent(eventId: String): Event.TaskCreated = Event.TaskCreated(
        eventId = eventId,
        urgency = Urgency.MEDIUM,
        timestamp = Clock.System.now(),
        eventSource = EventSource.Agent("agent-A"),
        taskId = "task-1",
        description = "Do the thing",
        assignedTo = "agent-B",
    )

    @Test
    fun `releasing one subscription leaves every other subscriber on that type listening`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val keeper = mutableListOf<String>()
        val leaver = mutableListOf<String>()

        bus.subscribeSuspending(
            agentId = "keeper",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { event, _ -> keeper += event.eventId },
        )
        val leaverSubscription = bus.subscribeSuspending(
            agentId = "leaver",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { event, _ -> leaver += event.eventId },
        )

        bus.publish(taskEvent("evt-1"))
        runCurrent()

        bus.unsubscribeSuspending(leaverSubscription)

        bus.publish(taskEvent("evt-2"))
        runCurrent()

        assertEquals(listOf("evt-1", "evt-2"), keeper, "The surviving subscriber must keep receiving")
        assertEquals(listOf("evt-1"), leaver, "The released subscriber must stop receiving")
    }

    @Test
    fun `releasing the same subscription twice is a no-op`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<String>()

        val first = bus.subscribeSuspending(
            agentId = "first",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, _ -> received += "first" },
        )
        bus.subscribeSuspending(
            agentId = "second",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, _ -> received += "second" },
        )

        bus.unsubscribeSuspending(first)
        bus.unsubscribeSuspending(first)

        bus.publish(taskEvent("evt-1"))
        runCurrent()

        assertEquals(listOf("second"), received)
    }

    @Test
    fun `two subscribers sharing an agent id are still told apart`() = runTest {
        // Both subscriptions carry the same subscriptionId — `eventTypes + "/" + agentId` — so
        // only object identity can distinguish them. Matching by value would release both.
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<String>()

        val first = bus.subscribeSuspending(
            agentId = "same-agent",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, _ -> received += "first" },
        )
        val second = bus.subscribeSuspending(
            agentId = "same-agent",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, _ -> received += "second" },
        )

        assertEquals(first.subscriptionId, second.subscriptionId, "Precondition: the ids collide")

        bus.unsubscribeSuspending(first)

        bus.publish(taskEvent("evt-1"))
        runCurrent()

        assertEquals(listOf("second"), received)
    }

    @Test
    fun `unsubscribing by event type still removes every handler`() = runTest {
        // The blunt overload is kept for compatibility; this pins its semantics so a future
        // change cannot quietly narrow it.
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<String>()

        bus.subscribeSuspending(
            agentId = "first",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, _ -> received += "first" },
        )
        bus.subscribeSuspending(
            agentId = "second",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, _ -> received += "second" },
        )

        bus.unsubscribe(Event.TaskCreated.EVENT_TYPE)

        bus.publish(taskEvent("evt-1"))
        runCurrent()

        assertEquals(emptyList(), received)
    }

    @Test
    fun `each handler is given its own subscription rather than the first registered one`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val seenAgentIds = mutableListOf<String>()

        bus.subscribeSuspending(
            agentId = "first",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, subscription ->
                seenAgentIds += (subscription as EventSubscription).agentId
            },
        )
        bus.subscribeSuspending(
            agentId = "second",
            eventType = Event.TaskCreated.EVENT_TYPE,
            handler = EventHandler { _, subscription ->
                seenAgentIds += (subscription as EventSubscription).agentId
            },
        )

        bus.publish(taskEvent("evt-1"))
        runCurrent()

        assertTrue("first" in seenAgentIds && "second" in seenAgentIds, "Saw $seenAgentIds")
    }
}
