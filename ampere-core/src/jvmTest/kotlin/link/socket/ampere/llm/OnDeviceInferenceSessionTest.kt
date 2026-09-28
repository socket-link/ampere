package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.domain.routing.local.OnDeviceAvailability
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.utils.SilentEventLogger
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * The whole on-device path, end to end (AMPR-327): a prompt goes in through
 * [OnDeviceInferenceSession.ask], the relay routes it, the dispatching client
 * executes it, the door persists and dispatches its telemetry, and the session's
 * state says what happened.
 *
 * `runBlocking`, not `runTest`: the door hops to the IO dispatcher to persist, and
 * a virtual clock runs past a `withTimeout` while that hop is in flight.
 */
class OnDeviceInferenceSessionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventApi.Handle

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventApi.open(
            agentId = OnDeviceInferenceSession.AGENT_ID,
            scope = scope,
            logger = SilentEventLogger(),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `answers on the device when the device can`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(
            capacity = available(),
            respond = { Result.success("An answer from the device.") },
        )
        val cloud = RecordingCloud()
        val session = session(engine, cloud)

        val answer = session.ask("What is an Arc?").answer()

        assertEquals("An answer from the device.", answer.text)
        assertEquals(InferenceLocality.ON_DEVICE, answer.locality)
        assertEquals(AIProvider_OnDevice.id, answer.providerId)
        assertEquals(ON_DEVICE_MODEL, answer.modelId)
        assertEquals(1, engine.generateCount)
        assertEquals(0, cloud.callCount, "a prompt answered on the device must not reach the cloud")

        val settled = session.awaitState { it.servedOnDevice == 1 && !it.isInUse }
        assertEquals(0, settled.servedInCloud)
        assertIs<OnDeviceAvailability.Available>(settled.availability)
    }

    @Test
    fun `reads as in use for exactly as long as the device is generating`() = runBlocking<Unit> {
        val engine = GatedEngine(available())
        val session = session(engine, cloud = null)

        assertFalse(session.state.value.isInUse)

        val asking = async { session.ask("Summarise this.") }

        val generating = session.awaitState { it.isInUse }
        assertEquals(ON_DEVICE_MODEL, generating.modelId)
        assertEquals(1, generating.inFlight.size)

        engine.release("Done.")

        assertEquals("Done.", asking.await().answer().text)
        session.awaitState { !it.isInUse && it.servedOnDevice == 1 }
    }

    @Test
    fun `an on-device only session sends nothing when the device cannot serve`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(capacity = unavailable(LocalUnavailableReason.NOT_ENABLED))
        val session = session(engine, cloud = null)

        val outcome = session.ask("What is an Arc?")

        val failed = assertIs<LocalFirstOutcome.Failed>(outcome)
        assertEquals(LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE, failed.reason)
        assertEquals(LocalUnavailableReason.NOT_ENABLED, failed.detail)
        assertEquals(0, engine.generateCount)

        val availability = assertIs<OnDeviceAvailability.Unavailable>(session.state.value.availability)
        assertEquals(LocalUnavailableReason.NOT_ENABLED, availability.reason)
        assertFalse(session.state.value.isInUse)
    }

    @Test
    fun `falls back to the cloud and says so when the device cannot serve`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(capacity = unavailable(LocalUnavailableReason.MODEL_NOT_READY))
        val cloud = RecordingCloud(response = "An answer from the cloud.")
        val session = session(engine, cloud)

        val answer = session.ask("What is an Arc?").answer()

        assertEquals("An answer from the cloud.", answer.text)
        assertEquals(InferenceLocality.CLOUD, answer.locality)
        assertEquals(0, engine.generateCount)
        assertEquals(1, cloud.callCount)

        val settled = session.awaitState { it.servedInCloud == 1 && it.lastFallback != null }
        assertEquals(0, settled.servedOnDevice)
        assertFalse(settled.isInUse)
        assertEquals(LocalUnavailableReason.MODEL_NOT_READY, settled.lastFallback?.reason)
        assertEquals(answer.modelId, settled.lastFallback?.servedBy)
    }

    @Test
    fun `a prompt too large for the device goes to the cloud when there is one`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(capacity = available())
        val cloud = RecordingCloud()
        val session = session(engine, cloud)

        val answer = session.ask(OVERSIZED_PROMPT).answer()

        assertEquals(InferenceLocality.CLOUD, answer.locality)
        assertEquals(0, engine.generateCount, "a prompt that does not fit must not be handed to the device")
        assertEquals(1, cloud.callCount)
    }

    @Test
    fun `a prompt too large for the device is refused when there is no cloud`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(capacity = available())
        val session = session(engine, cloud = null)

        val outcome = session.ask(OVERSIZED_PROMPT)

        val failed = assertIs<LocalFirstOutcome.Failed>(outcome)
        assertEquals(LocalFirstOutcome.FailureReason.PROMPT_TOO_LARGE, failed.reason)
        assertEquals(0, engine.generateCount)
    }

    @Test
    fun `routes against the context window the engine reports`() = runBlocking<Unit> {
        // The catalog seeds 4096 tokens. This engine has four times that so the same
        // prompt the device refused above now fits.
        val engine = FakeLocalInferenceEngine(capacity = available(maxContextTokens = 16_384))
        val session = session(engine, cloud = null)

        val answer = session.ask(OVERSIZED_PROMPT).answer()

        assertEquals(InferenceLocality.ON_DEVICE, answer.locality)
        assertEquals(1, engine.generateCount)
    }

    @Test
    fun `a device failure is returned and not retried in the cloud`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(
            capacity = available(),
            respond = { Result.failure(IllegalStateException("guardrail violation")) },
        )
        val cloud = RecordingCloud()
        val session = session(engine, cloud)

        val outcome = session.ask("What is an Arc?")

        val failed = assertIs<LocalFirstOutcome.Failed>(outcome)
        assertEquals(LocalFirstOutcome.FailureReason.ON_DEVICE_GENERATION_FAILED, failed.reason)
        assertEquals("guardrail violation", failed.detail)
        assertEquals(1, engine.generateCount)
        assertEquals(0, cloud.callCount, "a prompt shown as on-device must not be re-sent to the cloud")

        val settled = session.awaitState { it.failedOnDevice == 1 }
        assertFalse(settled.isInUse)
        assertEquals(0, settled.servedOnDevice)
    }

    @Test
    fun `an engine that does not name its provider is still routed to`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(
            capacity = LocalCapacity(available = true, modelId = ON_DEVICE_MODEL, providerId = null),
        )
        val session = session(engine, cloud = null)

        val answer = session.ask("What is an Arc?").answer()

        assertEquals(InferenceLocality.ON_DEVICE, answer.locality)
        assertEquals(1, engine.generateCount)
    }

    @Test
    fun `a blank prompt is refused before anything is asked of the device`() = runBlocking<Unit> {
        val engine = FakeLocalInferenceEngine(capacity = available())
        val session = session(engine, cloud = null)

        val outcome = session.ask("   ")

        val failed = assertIs<LocalFirstOutcome.Failed>(outcome)
        assertEquals(LocalFirstOutcome.FailureReason.EMPTY_PROMPT, failed.reason)
        assertEquals(0, engine.probeCount)
        assertEquals(0, engine.generateCount)
    }

    @Test
    fun `every call leaves its telemetry under its own run`() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<Event>()
        for (eventType in listOf(ProviderCallStartedEvent.EVENT_TYPE, ProviderCallCompletedEvent.EVENT_TYPE)) {
            door.bus.subscribeSuspending(
                agentId = "session-test-observer",
                eventType = eventType,
                handler = EventHandler { event, _ -> seen += event },
            )
        }
        val session = session(FakeLocalInferenceEngine(capacity = available()), cloud = null)

        val first = session.ask("First question").answer()
        val second = session.ask("Second question").answer()

        assertTrue(first.runId != second.runId)
        session.awaitState { it.servedOnDevice == 2 }
        withTimeout(TIMEOUT_MS) {
            while (seen.size < 4) delay(10)
        }

        for (answer in listOf(first, second)) {
            val started = seen.filterIsInstance<ProviderCallStartedEvent>().single { it.workflowId == answer.runId }
            val completed = seen.filterIsInstance<ProviderCallCompletedEvent>().single { it.workflowId == answer.runId }

            assertEquals(AIProvider_OnDevice.id, started.providerId)
            assertEquals(answer.routingReason, started.routingReason)
            assertTrue(completed.success)
            // A call with no phase is filed under UNKNOWN in the trace.
            assertEquals(CognitivePhase.EXECUTE, started.cognitivePhase)
            assertEquals(CognitivePhase.EXECUTE, completed.cognitivePhase)
        }
    }

    private fun session(
        engine: LocalInferenceEngine,
        cloud: UpstreamLlmClient?,
    ) = OnDeviceInferenceSession(
        engine = engine,
        eventApi = door.api,
        scope = scope,
        cloud = cloud,
    )

    private fun LocalFirstOutcome.answer(): LocalFirstAnswer =
        when (this) {
            is LocalFirstOutcome.Answered -> answer
            is LocalFirstOutcome.Failed -> error("expected an answer but the call failed: $reason ($detail)")
        }

    private suspend fun OnDeviceInferenceSession.awaitState(
        predicate: (OnDeviceInferenceState) -> Boolean,
    ): OnDeviceInferenceState = withTimeout(TIMEOUT_MS) { state.first(predicate) }

    private fun available(maxContextTokens: Int = 4_096) = LocalCapacity(
        available = true,
        modelId = ON_DEVICE_MODEL,
        maxContextTokens = maxContextTokens,
        providerId = AIProvider_OnDevice.id,
    )

    private fun unavailable(reason: String) = LocalCapacity(
        available = false,
        providerId = AIProvider_OnDevice.id,
        reason = reason,
    )

    /** An engine that holds its generation open until the test lets it finish. */
    private class GatedEngine(private val capacity: LocalCapacity) : LocalInferenceEngine {
        private val gate = CompletableDeferred<String>()

        fun release(text: String) {
            gate.complete(text)
        }

        override suspend fun probe(): LocalCapacity = capacity

        override suspend fun generate(prompt: String): Result<String> = Result.success(gate.await())
    }

    private class RecordingCloud(
        private val response: String = "cloud-response",
    ) : UpstreamLlmClient {
        @Volatile
        var callCount: Int = 0
            private set

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            callCount++
            return ChatCompletion(
                id = "cloud",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = response),
                    ),
                ),
            )
        }
    }

    private companion object {
        val ON_DEVICE_MODEL: String = AIModel_OnDevice.AppleFoundationModels.name

        const val TIMEOUT_MS = 5_000L

        /** About 5,000 tokens by the estimator's four-characters-per-token rule. */
        val OVERSIZED_PROMPT: String = "word ".repeat(4_000)
    }
}
