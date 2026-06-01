package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.Affordance
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription

/**
 * Validates that [HumanInteractionEvent] is a true specialisation of [EmissionEvent]:
 * - [HumanInteractionEvent.InputRequested] is type-compatible with [EmissionEvent.Produced]
 * - Bus subscribers on [EmissionEvent.Produced.EVENT_TYPE] receive [InputRequested] events
 * - Specialised subscribers on [HumanInteractionEvent.InputRequested.EVENT_TYPE] receive only those
 * - [EventRegistry] includes all three new event types
 * - JSON round-trip preserves attribution fields
 */
class HumanInteractionEventSpecializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun decisionEmission(id: String = "em-1") = Emission(
        id = id,
        kind = EmissionKind.Decision,
        payload = EmissionPayload.Decision(prompt = "Should we proceed?", context = "Risk: low"),
        affordances = listOf(
            Affordance("aff-yes", "Yes", JsonPrimitive("yes")),
            Affordance("aff-no", "No", JsonPrimitive("no")),
        ),
        confidence = null,
        provenance = EmissionProvenance(
            runId = null, workflowId = null, sourceEventId = null,
            toolInvocationId = null, pluginId = null, modelId = null,
            inputDigest = "deadbeefdeadbeef",
        ),
        dedupKey = null,
        producedAt = now,
    )

    // ── Type hierarchy ──────────────────────────────────────────────────────

    @Test
    fun `InputRequested is a subtype of EmissionEvent`() {
        val event = inputRequested()
        assertIs<EmissionEvent>(event)
    }

    @Test
    fun `InputRequested is a subtype of EmissionEvent dot Produced`() {
        val event = inputRequested()
        assertIs<EmissionEvent.Produced>(event)
    }

    @Test
    fun `InputRequested is a subtype of HumanInteractionEvent`() {
        val event = inputRequested()
        assertIs<HumanInteractionEvent>(event)
    }

    @Test
    fun `InputProvided is a subtype of EmissionEvent dot Resolved`() {
        val event = inputProvided()
        assertIs<EmissionEvent.Resolved>(event)
    }

    @Test
    fun `InputProvided is a subtype of HumanInteractionEvent`() {
        val event = inputProvided()
        assertIs<HumanInteractionEvent>(event)
    }

    @Test
    fun `InputRequested emissionId is derived from the wrapped emission`() {
        val event = inputRequested()
        assertEquals("em-1", event.emissionId)
        assertEquals("em-1", event.emission.id)
    }

    // ── Bus polymorphic delivery ────────────────────────────────────────────

    @Test
    fun `bus subscriber on EmissionProduced receives InputRequested`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<EmissionEvent>()

            bus.subscribe<EmissionEvent, EventSubscription.ByEventClassType>(
                agentId = "base-subscriber",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val event = inputRequested()
            bus.publish(event)

            val seen = withTimeout(5.seconds) { received.await() }
            assertNotNull(seen)
            assertIs<HumanInteractionEvent.InputRequested>(seen)
        }
    }

    @Test
    fun `bus subscriber on HumanInteractionRequested receives only InputRequested`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<HumanInteractionEvent.InputRequested>()

            bus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
                agentId = "human-subscriber",
                eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val base = EmissionEvent.BaseProduced(
                eventId = "evt-base",
                timestamp = now,
                eventSource = EventSource.Agent("agent-1"),
                emission = decisionEmission("em-base"),
            )
            val human = inputRequested(emissionId = "em-human")

            bus.publish(base)
            bus.publish(human)

            val seen = withTimeout(5.seconds) { received.await() }
            assertEquals("em-human", seen.emissionId)
        }
    }

    @Test
    fun `bus subscriber on EmissionResolved receives InputProvided`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<EmissionEvent>()

            bus.subscribe<EmissionEvent, EventSubscription.ByEventClassType>(
                agentId = "resolved-subscriber",
                eventType = EmissionEvent.Resolved.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val event = inputProvided()
            bus.publish(event)

            val seen = withTimeout(5.seconds) { received.await() }
            assertNotNull(seen)
            assertIs<HumanInteractionEvent.InputProvided>(seen)
        }
    }

    // ── EventRegistry ───────────────────────────────────────────────────────

    @Test
    fun `EventRegistry includes all three HumanInteractionEvent types`() {
        assertTrue(HumanInteractionEvent.InputRequested.EVENT_TYPE in EventRegistry.allEventTypes)
        assertTrue(HumanInteractionEvent.InputProvided.EVENT_TYPE in EventRegistry.allEventTypes)
        assertTrue(HumanInteractionEvent.RequestTimedOut.EVENT_TYPE in EventRegistry.allEventTypes)
    }

    // ── JSON serialization ──────────────────────────────────────────────────

    @Test
    fun `InputRequested JSON round-trip preserves attribution fields`() {
        val event = HumanInteractionEvent.InputRequested(
            eventId = "evt-1",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            urgency = Urgency.HIGH,
            emission = decisionEmission(),
            requestId = "req-1",
            agentId = "agent-1",
            ticketId = "ticket-42",
            taskId = "task-7",
        )

        val encoded = json.encodeToString(HumanInteractionEvent.serializer(), event)
        val decoded = json.decodeFromString(HumanInteractionEvent.serializer(), encoded)

        val inputReq = decoded as HumanInteractionEvent.InputRequested
        assertEquals("req-1", inputReq.requestId)
        assertEquals("agent-1", inputReq.agentId)
        assertEquals("ticket-42", inputReq.ticketId)
        assertEquals("task-7", inputReq.taskId)
        assertEquals("em-1", inputReq.emissionId)
        assertTrue(encoded.contains("\"type\":\"HumanInteractionEvent.InputRequested\""))
    }

    @Test
    fun `InputProvided JSON round-trip preserves respondedBy`() {
        val event = inputProvided(respondedBy = "miley")

        val encoded = json.encodeToString(HumanInteractionEvent.serializer(), event)
        val decoded = json.decodeFromString(HumanInteractionEvent.serializer(), encoded)

        val inputProv = decoded as HumanInteractionEvent.InputProvided
        assertEquals("miley", inputProv.respondedBy)
        assertEquals("req-1", inputProv.requestId)
        assertTrue(encoded.contains("\"type\":\"HumanInteractionEvent.InputProvided\""))
    }

    @Test
    fun `RequestTimedOut JSON round-trip preserves timeoutMinutes`() {
        val event = HumanInteractionEvent.RequestTimedOut(
            eventId = "evt-3",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            urgency = Urgency.HIGH,
            emissionId = "em-1",
            requestId = "req-1",
            agentId = "agent-1",
            timeoutMinutes = 30L,
        )

        val encoded = json.encodeToString(HumanInteractionEvent.serializer(), event)
        val decoded = json.decodeFromString(HumanInteractionEvent.serializer(), encoded)

        val timedOut = decoded as HumanInteractionEvent.RequestTimedOut
        assertEquals(30L, timedOut.timeoutMinutes)
        assertTrue(encoded.contains("\"type\":\"HumanInteractionEvent.RequestTimedOut\""))
    }

    // ── parentEventTypes ───────────────────────────────────────────────────

    @Test
    fun `InputRequested parentEventTypes includes EmissionProduced`() {
        val event = inputRequested()
        assertTrue(EmissionEvent.Produced.EVENT_TYPE in event.parentEventTypes)
    }

    @Test
    fun `InputProvided parentEventTypes includes EmissionResolved`() {
        val event = inputProvided()
        assertTrue(EmissionEvent.Resolved.EVENT_TYPE in event.parentEventTypes)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun inputRequested(emissionId: String = "em-1") = HumanInteractionEvent.InputRequested(
        eventId = "evt-1",
        timestamp = now,
        eventSource = EventSource.Agent("agent-1"),
        urgency = Urgency.HIGH,
        emission = decisionEmission(emissionId),
        requestId = "req-1",
        agentId = "agent-1",
        ticketId = null,
        taskId = null,
    )

    private fun inputProvided(respondedBy: String? = null) = HumanInteractionEvent.InputProvided(
        eventId = "evt-2",
        timestamp = now,
        eventSource = EventSource.Human,
        urgency = Urgency.HIGH,
        emissionId = "em-1",
        affordanceId = "aff-yes",
        replyContext = JsonObject(mapOf("type" to JsonPrimitive("free-text"), "text" to JsonPrimitive("yes"))),
        requestId = "req-1",
        agentId = "agent-1",
        respondedBy = respondedBy,
    )
}
