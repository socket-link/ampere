package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.util.randomUUID

/**
 * AMPR-332: `emission {}` must hold exactly one reply-router subscription while its block runs
 * and release it on every exit path, so repeated calls don't pile up handlers on the bus.
 */
class EmissionSubscriptionLifecycleTest {

    /** Tracks the bus's live handler count per event type through its subscribe/unsubscribe hooks. */
    private class CountingEventLogger : EventLogger {
        private val live = mutableMapOf<EventType, Int>()
        val errors = mutableListOf<String>()

        fun liveHandlers(eventType: EventType): Int = live[eventType] ?: 0

        override fun logPublish(event: Event) = Unit

        override fun logSubscription(eventType: EventType, subscription: Subscription) {
            live[eventType] = liveHandlers(eventType) + 1
        }

        override fun logUnsubscription(eventType: EventType, subscription: Subscription) {
            live[eventType] = liveHandlers(eventType) - 1
        }

        override fun logError(message: String, throwable: Throwable?) {
            errors += message
        }

        override fun logInfo(message: String) = Unit
    }

    private val source = EventSource.Agent("test-agent")

    private fun resolvedFor(emissionId: EmissionId) = EmissionEvent.BaseResolved(
        eventId = randomUUID(),
        timestamp = Clock.System.now(),
        eventSource = EventSource.Human,
        urgency = Urgency.HIGH,
        emissionId = emissionId,
        affordanceId = "confirm",
    )

    @Test
    fun `repeated emission calls leave the reply handler count unchanged`() = runTest {
        coroutineScope {
            val logger = CountingEventLogger()
            val bus = EventSerialBus(scope = this, logger = logger)
            val registry = EmissionReplyRegistry()

            repeat(3) { i ->
                emission(source, bus, registry, principal = Principal.Ambient, parentEmissionId = null) {
                    sense(label = "cpu", value = "$i")
                }
            }

            assertEquals(0, logger.liveHandlers(EmissionEvent.Resolved.EVENT_TYPE))
            assertEquals(0, logger.liveHandlers(HumanInteractionEvent.InputProvided.EVENT_TYPE))
        }
    }

    @Test
    fun `a reply after three completed calls reaches exactly one router`() = runTest {
        coroutineScope {
            val logger = CountingEventLogger()
            val bus = EventSerialBus(scope = this, logger = logger)
            val registry = EmissionReplyRegistry()
            val produced = CompletableDeferred<EmissionEvent.BaseProduced>()

            repeat(3) { i ->
                emission(source, bus, registry, principal = Principal.Ambient, parentEmissionId = null) {
                    sense(label = "cpu", value = "$i")
                }
            }

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { event, _ -> if (event.emission.kind == EmissionKind.Decision) produced.complete(event) }

            val reply = async {
                emission(source, bus, registry, principal = Principal.Ambient, parentEmissionId = null) {
                    ask(prompt = "Proceed?", timeout = 5.seconds)
                }
            }

            val event = withTimeout(5.seconds) { produced.await() }

            // One router handler means the reply below reaches replyRegistry.deliver exactly once.
            assertEquals(1, logger.liveHandlers(EmissionEvent.Resolved.EVENT_TYPE))
            assertEquals(0, logger.liveHandlers(HumanInteractionEvent.InputProvided.EVENT_TYPE))

            bus.publish(resolvedFor(event.emission.id))

            assertEquals(event.emission.id, reply.await().emissionId)
            assertEquals(0, logger.liveHandlers(EmissionEvent.Resolved.EVENT_TYPE))
            assertTrue(logger.errors.isEmpty(), "handler errors: ${logger.errors}")
        }
    }

    @Test
    fun `InputProvided on the bus resumes askHuman without a handler error`() = runTest {
        coroutineScope {
            val logger = CountingEventLogger()
            val bus = EventSerialBus(scope = this, logger = logger)
            val registry = EmissionReplyRegistry()
            val requested = CompletableDeferred<HumanInteractionEvent.InputRequested>()

            val reply = async {
                emission(source, bus, registry, principal = Principal.Ambient, parentEmissionId = null) {
                    askHuman(
                        prompt = "Which branch?",
                        agentId = "test-agent",
                        timeout = 5.seconds,
                        onProduced = { requested.complete(it) },
                    )
                }
            }

            val request = withTimeout(5.seconds) { requested.await() }
            bus.publish(
                HumanInteractionEvent.InputProvided(
                    eventId = randomUUID(),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Human,
                    urgency = Urgency.HIGH,
                    emissionId = request.emission.id,
                    affordanceId = "free-text-resp",
                    requestId = request.requestId,
                    agentId = "test-agent",
                    respondedBy = "tester",
                ),
            )

            val resolved = assertIs<HumanInteractionEvent.InputProvided>(reply.await())
            assertEquals("tester", resolved.respondedBy)
            assertTrue(logger.errors.isEmpty(), "handler errors: ${logger.errors}")
        }
    }

    @Test
    fun `emission releases its reply handler when the block throws`() = runTest {
        coroutineScope {
            val logger = CountingEventLogger()
            val bus = EventSerialBus(scope = this, logger = logger)

            val result = runCatching {
                emission<Unit>(
                    eventSource = source,
                    eventSerialBus = bus,
                    replyRegistry = EmissionReplyRegistry(),
                    principal = Principal.Ambient,
                    parentEmissionId = null,
                ) { error("boom") }
            }

            assertIs<IllegalStateException>(result.exceptionOrNull())
            assertEquals(0, logger.liveHandlers(EmissionEvent.Resolved.EVENT_TYPE))
        }
    }

    @Test
    fun `emission releases its reply handler when the caller is cancelled`() = runTest {
        coroutineScope {
            val logger = CountingEventLogger()
            val bus = EventSerialBus(scope = this, logger = logger)
            val produced = CompletableDeferred<Unit>()

            bus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
                agentId = "test-sub",
                eventType = EmissionEvent.Produced.EVENT_TYPE,
            ) { _, _ -> produced.complete(Unit) }

            val job = launch {
                // The cancelled ask surfaces as EmissionTimeout; keep it from failing the test scope.
                runCatching {
                    emission(
                        eventSource = source,
                        eventSerialBus = bus,
                        replyRegistry = EmissionReplyRegistry(),
                        principal = Principal.Ambient,
                        parentEmissionId = null,
                    ) {
                        ask(prompt = "Proceed?", timeout = 30.minutes)
                    }
                }
            }

            withTimeout(5.seconds) { produced.await() }
            assertEquals(1, logger.liveHandlers(EmissionEvent.Resolved.EVENT_TYPE))

            job.cancelAndJoin()

            assertEquals(0, logger.liveHandlers(EmissionEvent.Resolved.EVENT_TYPE))
        }
    }
}
