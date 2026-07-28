package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.util.randomUUID

/**
 * Validates the authoring-level builders added for AMPR-238 Option A —
 * [EmissionScope.ask] (prompt overload), [EmissionScope.confirm], [EmissionScope.emit],
 * [EmissionScope.sense] — plus caller-supplied provenance/surfaces and the `publish` seam
 * that lets a host wrap outgoing events instead of publishing directly to [EventSerialBus].
 */
class EmissionScopeTest {

    private fun resolvedFor(emissionId: EmissionId) = EmissionEvent.BaseResolved(
        eventId = randomUUID(),
        timestamp = Clock.System.now(),
        eventSource = EventSource.Human,
        urgency = Urgency.HIGH,
        emissionId = emissionId,
        affordanceId = "confirm",
    )

    @Test
    fun `ask prompt overload publishes Decision emission and awaits reply`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val askDeferred = async {
                emission(EventSource.Agent("test-agent"), bus, registry) {
                    ask(
                        prompt = "Proceed?",
                        affordances = {
                            affordance("Yes")
                            affordance("No")
                        },
                        timeout = 5.seconds,
                    )
                }
            }

            val event = withTimeout(5.seconds) { published.await() }
            assertEquals(EmissionKind.Decision, event.emission.kind)
            assertIs<EmissionPayload.Decision>(event.emission.payload)
            assertEquals(2, event.emission.affordances.size)

            registry.deliver(resolvedFor(event.emission.id))
            askDeferred.await()
        }
    }

    @Test
    fun `confirm defaults to Confirm Cancel affordances and computes dedupKey`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val confirmDeferred = async {
                emission(EventSource.Agent("test-agent"), bus, registry) {
                    confirm(
                        action = "Delete branch",
                        dangerLevel = DangerLevel.HIGH,
                        timeout = 5.seconds,
                    )
                }
            }

            val event = withTimeout(5.seconds) { published.await() }
            assertEquals(EmissionKind.Confirmation, event.emission.kind)
            assertEquals(listOf("Confirm", "Cancel"), event.emission.affordances.map { it.label })
            assertEquals(event.emission.computeDedupKey(), event.emission.dedupKey)
            assertTrue(event.emission.dedupKey != null)

            registry.deliver(resolvedFor(event.emission.id))
            confirmDeferred.await()
        }
    }

    @Test
    fun `emit publishes Prose emission without awaiting a reply`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val result = emission(EventSource.Agent("test-agent"), bus, registry) {
                emit(text = "Build finished", format = ProseFormat.PLAIN)
            }

            assertEquals(EmissionKind.Prose, result.kind)
            val event = withTimeout(5.seconds) { published.await() }
            assertEquals(result.id, event.emission.id)
        }
    }

    @Test
    fun `sense publishes Sensor emission without awaiting a reply`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()

            val result = emission(EventSource.Agent("test-agent"), bus, registry) {
                sense(label = "cpu", value = "42", unit = "%")
            }

            assertEquals(EmissionKind.Sensor, result.kind)
            assertIs<EmissionPayload.Sensor>(result.payload)
        }
    }

    @Test
    fun `caller-supplied provenance overrides the digest-only default`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()

            val result = emission(EventSource.Agent("test-agent"), bus, registry) {
                sense(
                    label = "cpu",
                    value = "42",
                    provenance = EmissionProvenance(runId = "run-1", inputDigest = "ignored"),
                )
            }

            assertEquals("run-1", result.provenance.runId)
        }
    }

    @Test
    fun `askHuman defaults provenance to digest-only when none supplied`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<HumanInteractionEvent.InputRequested>()

            bus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val askDeferred = async {
                emission(EventSource.Agent("test-agent"), bus, registry) {
                    askHuman(prompt = "Proceed?", agentId = "test-agent", timeout = 5.seconds)
                }
            }

            val event = withTimeout(5.seconds) { published.await() }
            assertNull(event.emission.provenance.runId)
            assertTrue(event.emission.provenance.inputDigest.isNotEmpty())

            registry.deliver(resolvedFor(event.emission.id))
            askDeferred.await()
        }
    }

    @Test
    fun `surfaces declared on a builder are carried onto the published Emission`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()

            val result = emission(EventSource.Agent("test-agent"), bus, registry) {
                sense(label = "cpu", value = "42", surfaces = listOf(Surface.Push, Surface.Console))
            }

            assertEquals(listOf(Surface.Push, Surface.Console), result.surfaces)
        }
    }

    @Test
    fun `publish seam lets a host intercept outgoing events instead of the raw bus`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val registry = EmissionReplyRegistry()
            val intercepted = mutableListOf<Event>()
            val busReceived = CompletableDeferred<Boolean>()

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "bus-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { _, _ -> busReceived.complete(true) }

            emission(
                eventSource = EventSource.Agent("test-agent"),
                eventSerialBus = bus,
                replyRegistry = registry,
                publish = { event -> intercepted.add(event) },
            ) {
                sense(label = "cpu", value = "42")
            }

            assertEquals(1, intercepted.size)
            assertIs<EmissionEvent.BaseProduced>(intercepted.single())
            assertFalse(busReceived.isCompleted)
        }
    }
}
