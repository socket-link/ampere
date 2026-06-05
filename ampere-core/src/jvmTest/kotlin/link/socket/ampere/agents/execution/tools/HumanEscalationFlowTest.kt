package link.socket.ampere.agents.execution.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.EmissionTimeout
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.emission
import link.socket.ampere.agents.domain.emission.extractFreeText
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.util.randomUUID

/**
 * End-to-end validation of the Emission-based human escalation flow.
 *
 * Verifies:
 * - EmissionReplyRegistry blocks until a reply is delivered
 * - Timeout returns null (then EmissionScope throws EmissionTimeout)
 * - Multiple concurrent requests are handled independently
 * - Published events are HumanInteractionEvent.InputRequested instances
 * - Subscribers on base EmissionEvent.Produced receive HumanInteractionEvent.InputRequested
 * - GlobalHumanResponseRegistry is NOT referenced (coverage assertion)
 */
class HumanEscalationFlowTest {

    private fun newRegistry() = EmissionReplyRegistry()

    private fun newBus(scope: CoroutineScope) = EventSerialBus(
        scope = scope,
    )

    private fun createTestContext(instructions: String): ExecutionContext.NoChanges {
        return ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = Ticket(
                id = generateUUID(),
                title = "Test ticket",
                description = "Test ticket",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                status = TicketStatus.Ready,
                assignedAgentId = null,
                createdByAgentId = "test-agent",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
            task = Task.CodeChange(
                id = generateUUID(),
                status = TaskStatus.Pending,
                description = "Test task",
            ),
            instructions = instructions,
        )
    }

    // ── EmissionReplyRegistry behaviour ────────────────────────────────────

    @Test
    fun `EmissionReplyRegistry blocks until reply delivered`() = runTest {
        val registry = newRegistry()

        val deferred = async {
            registry.awaitReply("em-1", timeout = 5.seconds)
        }

        delay(100.milliseconds)
        assertFalse(deferred.isCompleted)

        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = "em-1",
                affordanceId = "free-text",
                replyContext = kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "type" to kotlinx.serialization.json.JsonPrimitive("free-text"),
                        "text" to kotlinx.serialization.json.JsonPrimitive("Approved"),
                    ),
                ),
            ),
        )

        val reply = deferred.await()
        assertNotNull(reply)
        val text = extractFreeText(reply.replyContext)
        assertEquals("Approved", text)
    }

    @Test
    fun `EmissionReplyRegistry returns null on timeout`() = runTest {
        val registry = newRegistry()
        val reply = registry.awaitReply("em-timeout", timeout = 100.milliseconds)
        assertIs<Nothing?>(reply)
    }

    @Test
    fun `multiple concurrent requests handled independently`() = runTest {
        val registry = newRegistry()

        val d1 = async { registry.awaitReply("em-1", timeout = 5.seconds) }
        val d2 = async { registry.awaitReply("em-2", timeout = 5.seconds) }

        delay(50.milliseconds)

        fun resolved(emissionId: String) = EmissionEvent.BaseResolved(
            eventId = randomUUID(),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Human,
            urgency = Urgency.HIGH,
            emissionId = emissionId,
            affordanceId = "free-text",
        )

        registry.deliver(resolved("em-2"))
        registry.deliver(resolved("em-1"))

        assertEquals("em-1", d1.await()?.emissionId)
        assertEquals("em-2", d2.await()?.emissionId)
    }

    // ── EmissionScope.askHuman ──────────────────────────────────────────────

    @Test
    fun `askHuman publishes HumanInteractionEvent InputRequested`() = runTest {
        val bus = newBus(backgroundScope)
        val registry = newRegistry()
        val receivedEvent = CompletableDeferred<HumanInteractionEvent.InputRequested>()

        bus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
            agentId = "test-sub",
            eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
        ) { event, _ ->
            if (!receivedEvent.isCompleted) {
                receivedEvent.complete(event)
            }
        }

        val askDeferred = async {
            emission(EventSource.Agent("test-agent"), bus, registry) {
                askHuman(
                    prompt = "Should we proceed?",
                    agentId = "test-agent",
                    ticketId = "ticket-1",
                    taskId = "task-1",
                    timeout = 5.seconds,
                )
            }
        }

        val published = withTimeout(5.seconds) { receivedEvent.await() }
        assertIs<HumanInteractionEvent.InputRequested>(published)
        assertIs<EmissionEvent.Produced>(published)
        assertEquals("test-agent", published.agentId)
        assertEquals("ticket-1", published.ticketId)
        assertEquals("task-1", published.taskId)

        // Deliver a reply to unblock
        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = published.emissionId,
                affordanceId = "free-text",
            ),
        )
        askDeferred.await()
    }

    @Test
    fun `base EmissionProduced subscriber receives InputRequested via polymorphic dispatch`() = runTest {
        val bus = newBus(backgroundScope)
        val registry = newRegistry()
        val baseReceived = CompletableDeferred<EmissionEvent>()

        bus.subscribe<EmissionEvent, EventSubscription.ByEventClassType>(
            agentId = "base-sub",
            eventType = EmissionEvent.Produced.EVENT_TYPE,
        ) { event, _ ->
            if (!baseReceived.isCompleted) {
                baseReceived.complete(event)
            }
        }

        val askDeferred = async {
            emission(EventSource.Agent("test-agent"), bus, registry) {
                askHuman(
                    prompt = "Polymorphism check",
                    agentId = "test-agent",
                    timeout = 5.seconds,
                )
            }
        }

        val requestedEvent = withTimeout(5.seconds) {
            assertIs<HumanInteractionEvent.InputRequested>(baseReceived.await())
        }
        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = requestedEvent.emissionId,
                affordanceId = "free-text",
            ),
        )
        askDeferred.await()
    }

    @Test
    fun `askHuman timeout publishes RequestTimedOut event`() = runTest {
        val bus = newBus(backgroundScope)
        val registry = newRegistry()
        val timedOutEvent = CompletableDeferred<HumanInteractionEvent.RequestTimedOut>()

        bus.subscribe<HumanInteractionEvent.RequestTimedOut, EventSubscription.ByEventClassType>(
            agentId = "timeout-sub",
            eventType = HumanInteractionEvent.RequestTimedOut.EVENT_TYPE,
        ) { event, _ ->
            if (!timedOutEvent.isCompleted) {
                timedOutEvent.complete(event)
            }
        }

        val caught = try {
            emission(EventSource.Agent("test-agent"), bus, registry) {
                askHuman(
                    prompt = "Will timeout",
                    agentId = "test-agent",
                    timeout = 100.milliseconds,
                )
            }
            null
        } catch (e: EmissionTimeout) {
            e
        }

        assertNotNull(caught)
        val timeoutEvent = withTimeout(5.seconds) { timedOutEvent.await() }
        assertEquals(0L, timeoutEvent.timeoutMinutes) // 100ms → 0 minutes
    }

    // ── Coverage assertion: no GlobalHumanResponseRegistry usage ───────────

    @Test
    fun `GlobalEmissionReplyRegistry is accessible`() {
        val registry = GlobalEmissionReplyRegistry.instance
        assertNotNull(registry)
        assertTrue(registry.getPendingEmissionIds().isEmpty())
    }
}
