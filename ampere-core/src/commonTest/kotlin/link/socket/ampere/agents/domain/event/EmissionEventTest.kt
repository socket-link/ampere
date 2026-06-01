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
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.Affordance
import link.socket.ampere.agents.domain.emission.DangerLevel
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.domain.reasoning.Confidence
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription

class EmissionEventTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val provenance = EmissionProvenance(
        runId = "run-1",
        workflowId = "wf-1",
        sourceEventId = "src-1",
        toolInvocationId = null,
        pluginId = "plugin-x",
        modelId = "claude-sonnet-4-0",
        inputDigest = "deadbeefdeadbeef",
    )

    private fun confirmationEmission() = Emission(
        id = "em-1",
        kind = EmissionKind.Confirmation,
        payload = EmissionPayload.Confirmation(
            action = "delete branch",
            preview = "feature/x",
            dangerLevel = DangerLevel.HIGH,
        ),
        affordances = listOf(
            Affordance("aff-yes", "Yes", JsonPrimitive("yes")),
            Affordance("aff-no", "No", JsonPrimitive("no")),
        ),
        confidence = Confidence.MEDIUM,
        provenance = provenance,
        dedupKey = "abcdef0123456789",
        producedAt = now,
    )

    @Test
    fun `BaseProduced exposes the expected EVENT_TYPE`() {
        val event = EmissionEvent.BaseProduced(
            eventId = "evt-1",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            emission = confirmationEmission(),
        )
        assertEquals(EmissionEvent.Produced.EVENT_TYPE, event.eventType)
        assertEquals("EmissionProduced", event.eventType)
        assertEquals("em-1", event.emissionId)
    }

    @Test
    fun `BaseResolved exposes the expected EVENT_TYPE`() {
        val event = EmissionEvent.BaseResolved(
            eventId = "evt-2",
            timestamp = now,
            eventSource = EventSource.Human,
            emissionId = "em-1",
            affordanceId = "aff-yes",
            replyContext = JsonPrimitive("yes"),
        )
        assertEquals(EmissionEvent.Resolved.EVENT_TYPE, event.eventType)
        assertEquals("EmissionResolved", event.eventType)
        assertEquals("em-1", event.emissionId)
        assertEquals("aff-yes", event.affordanceId)
    }

    @Test
    fun `BaseProduced implements Produced interface`() {
        val event = EmissionEvent.BaseProduced(
            eventId = "evt-1",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            emission = confirmationEmission(),
        )
        assertIs<EmissionEvent.Produced>(event)
        assertIs<EmissionEvent>(event)
    }

    @Test
    fun `BaseResolved implements Resolved interface`() {
        val event = EmissionEvent.BaseResolved(
            eventId = "evt-2",
            timestamp = now,
            eventSource = EventSource.Human,
            emissionId = "em-1",
            affordanceId = "aff-yes",
        )
        assertIs<EmissionEvent.Resolved>(event)
        assertIs<EmissionEvent>(event)
    }

    @Test
    fun `EventRegistry includes both EmissionEvent base types`() {
        assertTrue(EmissionEvent.Produced.EVENT_TYPE in EventRegistry.allEventTypes)
        assertTrue(EmissionEvent.Resolved.EVENT_TYPE in EventRegistry.allEventTypes)
    }

    @Test
    fun `BaseProduced summary mentions the kind and id`() {
        val event = EmissionEvent.BaseProduced(
            eventId = "evt-1",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            emission = confirmationEmission(),
        )
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { (it as? EventSource.Agent)?.agentId ?: "human" },
        )
        assertTrue(summary.contains("Confirmation"))
        assertTrue(summary.contains("em-1"))
        assertTrue(summary.contains("agent-1"))
    }

    @Test
    fun `BaseResolved summary mentions both ids`() {
        val event = EmissionEvent.BaseResolved(
            eventId = "evt-2",
            timestamp = now,
            eventSource = EventSource.Human,
            emissionId = "em-1",
            affordanceId = "aff-yes",
        )
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { "human" },
        )
        assertTrue(summary.contains("em-1"))
        assertTrue(summary.contains("aff-yes"))
    }

    @Test
    fun `BaseProduced round-trips through JSON preserving nested Emission`() {
        val event = EmissionEvent.BaseProduced(
            eventId = "evt-1",
            timestamp = now,
            eventSource = EventSource.Agent("agent-1"),
            urgency = Urgency.HIGH,
            emission = confirmationEmission(),
        )

        val encoded = json.encodeToString(EmissionEvent.serializer(), event)
        val decoded = json.decodeFromString(EmissionEvent.serializer(), encoded)

        val produced = decoded as EmissionEvent.BaseProduced
        assertEquals(event.eventId, produced.eventId)
        assertEquals(event.emission, produced.emission)
        assertEquals(Urgency.HIGH, produced.urgency)
        assertTrue(encoded.contains("\"type\":\"EmissionEvent.Produced\""))
    }

    @Test
    fun `BaseResolved round-trips through JSON`() {
        val event = EmissionEvent.BaseResolved(
            eventId = "evt-2",
            timestamp = now,
            eventSource = EventSource.Human,
            emissionId = "em-1",
            affordanceId = "aff-yes",
            replyContext = JsonPrimitive("yes"),
        )

        val encoded = json.encodeToString(EmissionEvent.serializer(), event)
        val decoded = json.decodeFromString(EmissionEvent.serializer(), encoded)

        val resolved = decoded as EmissionEvent.BaseResolved
        assertEquals(event, resolved)
        assertTrue(encoded.contains("\"type\":\"EmissionEvent.Resolved\""))
    }

    @Test
    fun `bus delivers BaseProduced to a subscribed handler`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<EmissionEvent.BaseProduced>()

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "renderer-stub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val event = EmissionEvent.BaseProduced(
                eventId = "evt-1",
                timestamp = now,
                eventSource = EventSource.Agent("agent-1"),
                emission = confirmationEmission(),
            )
            bus.publish(event)

            val seen = withTimeout(5.seconds) { received.await() }
            assertNotNull(seen)
            assertEquals(event.eventId, seen.eventId)
            assertEquals("em-1", seen.emission.id)
        }
    }

    @Test
    fun `bus delivers BaseResolved to a subscribed handler`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val received = CompletableDeferred<EmissionEvent.BaseResolved>()

            bus.subscribe<EmissionEvent.BaseResolved, EventSubscription.ByEventClassType>(
                agentId = "originator",
                eventType = EmissionEvent.Resolved.EVENT_TYPE,
            ) { event, _ ->
                if (!received.isCompleted) received.complete(event)
            }

            val event = EmissionEvent.BaseResolved(
                eventId = "evt-2",
                timestamp = now,
                eventSource = EventSource.Human,
                emissionId = "em-1",
                affordanceId = "aff-yes",
                replyContext = JsonPrimitive("yes"),
            )
            bus.publish(event)

            val seen = withTimeout(5.seconds) { received.await() }
            assertEquals("aff-yes", seen.affordanceId)
        }
    }
}
