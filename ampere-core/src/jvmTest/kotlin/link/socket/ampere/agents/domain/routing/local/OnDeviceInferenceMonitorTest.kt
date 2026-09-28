package link.socket.ampere.agents.domain.routing.local

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * The live monitor against a real bus and a real door (AMPR-327): what it sees
 * is what was persisted and dispatched, and it leaves nothing subscribed behind.
 */
class OnDeviceInferenceMonitorTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventApi.Handle
    private val logger = SubscriptionCountingLogger()

    private val classifier = CatalogLocalityClassifier(InMemoryModelDescriptorRegistry())

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventApi.open(agentId = AGENT_ID, scope = scope, logger = logger)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `follows on-device calls published through the door`() = runBlocking<Unit> {
        val monitor = OnDeviceInferenceMonitor(classifier)
        val following = scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.follow(door.bus) }

        door.api.publish(started(), runId = RUN_ID).getOrThrow()
        val generating = withTimeout(TIMEOUT_MS) { monitor.state.first { it.isInUse } }
        assertEquals(ON_DEVICE_MODEL, generating.modelId)

        door.api.publish(completed(), runId = RUN_ID).getOrThrow()
        val settled = withTimeout(TIMEOUT_MS) { monitor.state.first { !it.isInUse && it.servedOnDevice == 1 } }
        assertEquals(RUN_ID, settled.lastCall?.call?.workflowId)

        following.cancelAndJoin()
    }

    @Test
    fun `releases every subscription when it stops following`() = runBlocking<Unit> {
        val monitor = OnDeviceInferenceMonitor(classifier)
        val before = logger.live(OnDeviceInferenceMonitor.FOLLOWED_EVENT_TYPES)

        val following = scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.follow(door.bus) }

        assertEquals(
            before + OnDeviceInferenceMonitor.FOLLOWED_EVENT_TYPES.size,
            logger.live(OnDeviceInferenceMonitor.FOLLOWED_EVENT_TYPES),
            "follow must be subscribed before it first suspends",
        )

        following.cancelAndJoin()

        assertEquals(before, logger.live(OnDeviceInferenceMonitor.FOLLOWED_EVENT_TYPES))

        // Nothing is listening, so a call published now must not move the state.
        door.api.publish(started(), runId = RUN_ID).getOrThrow()
        door.api.publish(completed(), runId = RUN_ID).getOrThrow()
        assertEquals(OnDeviceInferenceState(), monitor.state.value)
    }

    @Test
    fun `a probe that throws reads as unavailable with the failure as the reason`() = runBlocking<Unit> {
        val monitor = OnDeviceInferenceMonitor(classifier)
        val engine = object : LocalInferenceEngine {
            override suspend fun probe(): LocalCapacity = throw IllegalStateException("framework not linked")
            override suspend fun generate(prompt: String): Result<String> = Result.failure(IllegalStateException())
        }

        val capacity = monitor.probe(engine)

        assertFalse(capacity.available)
        assertEquals("framework not linked", capacity.reason)
        val availability = assertIs<OnDeviceAvailability.Unavailable>(monitor.state.value.availability)
        assertEquals("framework not linked", availability.reason)
    }

    @Test
    fun `a probe reports the engine as it is now`() = runBlocking<Unit> {
        val monitor = OnDeviceInferenceMonitor(classifier)
        val engine = FakeLocalInferenceEngine(
            capacity = LocalCapacity(
                available = true,
                modelId = ON_DEVICE_MODEL,
                maxContextTokens = 4_096,
                providerId = AIProvider_OnDevice.id,
            ),
        )

        monitor.probe(engine)

        assertIs<OnDeviceAvailability.Available>(monitor.state.value.availability)
        assertTrue(monitor.state.value.availabilityAsOf != null)
        assertEquals(1, engine.probeCount)
        assertEquals(0, engine.generateCount, "a probe must never generate")
    }

    private fun started() = ProviderCallStartedEvent(
        eventId = "monitor-test-start",
        timestamp = Clock.System.now(),
        eventSource = EventSource.Agent(AGENT_ID),
        workflowId = RUN_ID,
        agentId = AGENT_ID,
        providerId = AIProvider_OnDevice.id,
        modelId = ON_DEVICE_MODEL,
        routingReason = "capability:${AIProvider_OnDevice.id}",
    )

    private fun completed() = ProviderCallCompletedEvent(
        eventId = "monitor-test-complete",
        timestamp = Clock.System.now(),
        eventSource = EventSource.Agent(AGENT_ID),
        workflowId = RUN_ID,
        agentId = AGENT_ID,
        providerId = AIProvider_OnDevice.id,
        modelId = ON_DEVICE_MODEL,
        latencyMs = 12,
        success = true,
    )

    /** Counts live subscriptions per event type, from the bus's own subscribe and unsubscribe logs. */
    private class SubscriptionCountingLogger : EventLogger {
        private val subscribed = CopyOnWriteArrayList<EventType>()
        private val unsubscribed = CopyOnWriteArrayList<EventType>()

        fun live(eventTypes: List<EventType>): Int =
            subscribed.count { it in eventTypes } - unsubscribed.count { it in eventTypes }

        override fun logSubscription(eventType: EventType, subscription: Subscription) {
            subscribed += eventType
        }

        override fun logUnsubscription(eventType: EventType, subscription: Subscription) {
            unsubscribed += eventType
        }

        override fun logPublish(event: Event) = Unit

        override fun logError(message: String, throwable: Throwable?) = Unit

        override fun logInfo(message: String) = Unit
    }

    private companion object {
        const val AGENT_ID = "monitor-test-agent"
        const val RUN_ID = "monitor-test-run"
        const val TIMEOUT_MS = 5_000L

        val ON_DEVICE_MODEL: String = AIModel_OnDevice.AppleFoundationModels.name
    }
}
