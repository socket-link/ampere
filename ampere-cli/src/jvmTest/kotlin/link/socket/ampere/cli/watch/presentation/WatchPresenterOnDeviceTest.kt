package link.socket.ampere.cli.watch.presentation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.relay.EventRelayServiceImpl
import link.socket.ampere.agents.events.utils.SilentEventLogger
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.cli.ondevice.OnDeviceAssistant
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.llm.LocalFirstOutcome
import link.socket.ampere.llm.OnDeviceInferenceSession
import link.socket.ampere.ui.inference.OnDeviceIndicatorTone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The TUI's half of the on-device indicator (AMPR-375): the presenter turns the session's state
 * into the indicator the status bar shows, and marks the calls that ran on this device in the
 * feed. Both rest on events Ampere already emits; this checks they agree with what ran.
 */
class WatchPresenterOnDeviceTest {

    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `an answer from the device is marked in the feed and the indicator follows it`() = runBlocking<Unit> {
        val door = InMemoryEventDoor.open(
            agentId = OnDeviceInferenceSession.AGENT_ID,
            scope = scope,
            logger = SilentEventLogger(),
        )
        door.use {
            OnDeviceAssistant.Bound(engine = AvailableEngine("The answer."), eventApi = door.api).use { assistant ->
                val presenter = WatchPresenter(
                    eventRelayService = EventRelayServiceImpl(door.bus, door.repository),
                    onDeviceState = assistant.state,
                    localityClassifier = assistant.classifier,
                )
                presenter.start()
                try {
                    val cloudRow = awaitCloudRow(presenter, door)

                    assertIs<LocalFirstOutcome.Answered>(assistant.ask("What is an Arc?"))

                    val onDeviceRow = await {
                        presenter.getViewState().recentSignificantEvents.firstOrNull { it.onDevice }
                    }
                    assertTrue(
                        onDeviceRow.summaryText.startsWith("Answered on this device · "),
                        onDeviceRow.summaryText,
                    )
                    assertFalse(cloudRow.onDevice, "a cloud model's call is not marked")

                    val indicator = await {
                        presenter.getViewState().onDevice?.takeIf { it.detail.orEmpty().contains("1 answered here") }
                    }
                    assertEquals(OnDeviceIndicatorTone.READY, indicator.tone)
                    assertEquals("On-device ready", indicator.title)
                } finally {
                    presenter.stop()
                }
            }
        }
    }

    @Test
    fun `without an on-device engine the view is exactly as before`() = runBlocking<Unit> {
        InMemoryEventDoor.open(agentId = "cli", scope = scope, logger = SilentEventLogger()).use { door ->
            val presenter = WatchPresenter(EventRelayServiceImpl(door.bus, door.repository))
            presenter.start()
            try {
                val cloudRow = awaitCloudRow(presenter, door)

                assertFalse(cloudRow.onDevice)
                assertNull(presenter.getViewState().onDevice)
            } finally {
                presenter.stop()
            }
        }
    }

    /**
     * Publishes a cloud model's completion until the presenter shows one. The presenter subscribes
     * asynchronously, so a single event published straight after `start()` may arrive before it
     * listens.
     */
    private suspend fun awaitCloudRow(
        presenter: WatchPresenter,
        door: InMemoryEventDoor.Handle,
    ): SignificantEventSummary {
        val agentDoor = door.doorFor(CLOUD_AGENT)
        val published = mutableSetOf<String>()
        return await {
            val event = ProviderCallCompletedEvent(
                eventId = generateUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(CLOUD_AGENT),
                agentId = CLOUD_AGENT,
                providerId = AIProvider_Anthropic.id,
                modelId = AIModel_Claude.Sonnet_5.name,
                latencyMs = 900,
                success = true,
            )
            agentDoor.publish(event).getOrThrow()
            published += event.eventId
            presenter.getViewState().recentSignificantEvents.firstOrNull { it.eventId in published }
        }
    }

    private suspend fun <T : Any> await(timeout: Duration = 5.seconds, probe: suspend () -> T?): T =
        withTimeout(timeout) {
            var found = probe()
            while (found == null) {
                delay(50)
                found = probe()
            }
            found
        }

    /** An on-device engine that is always available and always answers [text]. */
    private class AvailableEngine(private val text: String) : LocalInferenceEngine {
        override suspend fun probe(): LocalCapacity = LocalCapacity(
            available = true,
            modelId = AIModel_OnDevice.AppleFoundationModels.name,
            maxContextTokens = 4_096,
            providerId = AIProvider_OnDevice.id,
        )

        override suspend fun generate(prompt: String): Result<String> = Result.success(text)
    }

    private companion object {
        const val CLOUD_AGENT = "code-agent"
    }
}
