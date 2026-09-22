package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.time.MutableClock
import link.socket.ampere.util.randomUUID

/**
 * Validates the authoring-level builders added for AMPR-238 Option A —
 * [EmissionScope.ask] (prompt overload), [EmissionScope.confirm], [EmissionScope.emit],
 * [EmissionScope.sense] — plus caller-supplied provenance/surfaces and the `publish` seam
 * that lets a host wrap outgoing events before they reach the door.
 *
 * F1a (AMPR-337): the scope publishes through [AgentEventApi], so every event it produces
 * is in the `EventStore` as well as on the bus. Runs in jvmTest because a door needs a
 * SQLite driver; see [InMemoryEventApi].
 *
 * Bodies use `runBlocking`, not `runTest`: the door persists on a real IO dispatcher, and
 * virtual time would skip past every `withTimeout` while that write is still in flight. The
 * door's own scope dispatches bus handlers inline, so observers see events in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmissionScopeTest {

    private val source = EventSource.Agent("test-agent")
    private val doorScope = TestScope(UnconfinedTestDispatcher())

    private fun door(clock: Clock = Clock.System): Pair<AgentEventApi, EventRepository> =
        InMemoryEventApi.create(agentId = "test-agent", clock = clock, scope = doorScope)

    private fun resolvedFor(emissionId: EmissionId) = EmissionEvent.BaseResolved(
        eventId = randomUUID(),
        timestamp = Clock.System.now(),
        eventSource = EventSource.Human,
        urgency = Urgency.HIGH,
        emissionId = emissionId,
        affordanceId = "confirm",
    )

    @Test
    fun `ask prompt overload publishes Decision emission and awaits reply`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            api.eventSerialBus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val askDeferred = async {
                emission(source, api, registry) {
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
    fun `ask persists the Produced event in the EventStore`() = runBlocking<Unit> {
        coroutineScope {
            val (api, repository) = door()
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            api.eventSerialBus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val askDeferred = async {
                emission(source, api, registry) {
                    ask(prompt = "Proceed?", timeout = 5.seconds)
                }
            }
            val event = withTimeout(5.seconds) { published.await() }
            registry.deliver(resolvedFor(event.emission.id))
            askDeferred.await()

            val stored = repository.getEventsByType(EmissionEvent.Produced.EVENT_TYPE).getOrThrow()
            assertEquals(1, stored.size)
            assertEquals(event.eventId, stored.single().eventId)
        }
    }

    @Test
    fun `confirm defaults to Confirm Cancel affordances and computes dedupKey`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            api.eventSerialBus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val confirmDeferred = async {
                emission(source, api, registry) {
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
    fun `emit publishes Prose emission without awaiting a reply`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            api.eventSerialBus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val result = emission(source, api, registry) {
                emit(text = "Build finished", format = ProseFormat.PLAIN)
            }

            assertEquals(EmissionKind.Prose, result.kind)
            val event = withTimeout(5.seconds) { published.await() }
            assertEquals(result.id, event.emission.id)
        }
    }

    @Test
    fun `sense publishes Sensor emission without awaiting a reply`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()

            val result = emission(source, api, registry) {
                sense(label = "cpu", value = "42", unit = "%")
            }

            assertEquals(EmissionKind.Sensor, result.kind)
            assertIs<EmissionPayload.Sensor>(result.payload)
        }
    }

    @Test
    fun `caller-supplied provenance overrides the digest-only default`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()

            val result = emission(source, api, registry) {
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
    fun `ambient runId is injected into default provenance and the stored envelope`() = runBlocking<Unit> {
        coroutineScope {
            val (api, repository) = door()
            val registry = EmissionReplyRegistry()

            val result = emission(
                eventSource = source,
                eventApi = api,
                replyRegistry = registry,
                runId = "ambient-run-id",
            ) {
                sense(label = "cpu", value = "42")
            }

            assertEquals("ambient-run-id", result.provenance.runId)
            val stored = repository.getEventsSinceSequence(0).getOrThrow()
            assertEquals(1, stored.size)
            assertEquals("ambient-run-id", stored.single().runId)
        }
    }

    @Test
    fun `askHuman defaults provenance to digest-only when none supplied`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<HumanInteractionEvent.InputRequested>()

            api.eventSerialBus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val askDeferred = async {
                emission(source, api, registry) {
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
    fun `askHuman persists InputRequested in the EventStore`() = runBlocking<Unit> {
        coroutineScope {
            val (api, repository) = door()
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<HumanInteractionEvent.InputRequested>()

            api.eventSerialBus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val askDeferred = async {
                emission(source, api, registry) {
                    askHuman(prompt = "Proceed?", agentId = "test-agent", timeout = 5.seconds)
                }
            }
            val event = withTimeout(5.seconds) { published.await() }
            registry.deliver(resolvedFor(event.emission.id))
            askDeferred.await()

            val stored = repository.getEventsByType(HumanInteractionEvent.InputRequested.EVENT_TYPE).getOrThrow()
            assertEquals(1, stored.size)
            val storedEvent = assertIs<HumanInteractionEvent.InputRequested>(stored.single())
            assertEquals(event.eventId, storedEvent.eventId)
            assertEquals(event.emission.id, storedEvent.emission.id)
        }
    }

    @Test
    fun `events are stamped from the door's clock`() = runBlocking<Unit> {
        coroutineScope {
            val fixed = Instant.parse("2026-03-01T12:00:00Z")
            val (api, _) = door(clock = MutableClock(fixed))
            val registry = EmissionReplyRegistry()
            val published = CompletableDeferred<EmissionEvent.BaseProduced>()

            api.eventSerialBus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> published.complete(event) }

            val result = emission(source, api, registry) {
                sense(label = "cpu", value = "42")
            }

            val event = withTimeout(5.seconds) { published.await() }
            assertEquals(fixed, result.producedAt)
            assertEquals(fixed, event.timestamp)
        }
    }

    @Test
    fun `surfaces declared on a builder are carried onto the published Emission`() = runBlocking<Unit> {
        coroutineScope {
            val (api, _) = door()
            val registry = EmissionReplyRegistry()

            val result = emission(source, api, registry) {
                sense(label = "cpu", value = "42", surfaces = listOf(Surface.Push, Surface.Console))
            }

            assertEquals(listOf(Surface.Push, Surface.Console), result.surfaces)
        }
    }

    @Test
    fun `publish seam lets a host wrap outgoing events on their way to the door`() = runBlocking<Unit> {
        coroutineScope {
            val (api, repository) = door()
            val registry = EmissionReplyRegistry()
            val intercepted = mutableListOf<Event>()

            emission(
                eventSource = source,
                eventApi = api,
                replyRegistry = registry,
                publish = { event ->
                    intercepted.add(event)
                    api.publish(event)
                },
            ) {
                sense(label = "cpu", value = "42")
            }

            assertEquals(1, intercepted.size)
            assertIs<EmissionEvent.BaseProduced>(intercepted.single())
            val stored = repository.getEventsByType(EmissionEvent.Produced.EVENT_TYPE).getOrThrow()
            assertEquals(listOf(intercepted.single().eventId), stored.map { it.eventId })
        }
    }

    @Test
    fun `a failed publish propagates as the block's failure`() = runBlocking<Unit> {
        coroutineScope {
            val (api, repository) = door()
            val registry = EmissionReplyRegistry()

            val failure = assertFailsWith<IllegalStateException> {
                emission(
                    eventSource = source,
                    eventApi = api,
                    replyRegistry = registry,
                    publish = { Result.failure(IllegalStateException("store unavailable")) },
                ) {
                    sense(label = "cpu", value = "42")
                }
            }

            assertEquals("store unavailable", failure.message)
            assertTrue(repository.getAllEvents().getOrThrow().isEmpty())
        }
    }
}
