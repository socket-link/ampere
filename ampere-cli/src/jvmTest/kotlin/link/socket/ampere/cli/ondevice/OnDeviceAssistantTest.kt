package link.socket.ampere.cli.ondevice

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.utils.SilentEventLogger
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceCount
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceGenerate
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceProbe
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.probe
import link.socket.ampere.domain.agent.bundled.OnDeviceAssistantAgent
import link.socket.ampere.llm.LocalFirstOutcome
import link.socket.ampere.llm.OnDeviceInferenceSession
import link.socket.ampere.ui.inference.describeFailure
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The CLI's on-device path end to end, short of the model (AMPR-375): a question goes through
 * [OnDeviceInferenceSession] and the relay to the helper engine, over the wire to a fake helper,
 * and the answer and its telemetry come back.
 *
 * `runBlocking`, not `runTest`: the door hops to the IO dispatcher to persist, and a virtual clock
 * runs past a `withTimeout` while that hop is in flight.
 */
class OnDeviceAssistantTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var scope: CoroutineScope
    private lateinit var fake: FakeAmpereHelper

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fake = FakeAmpereHelper(directory)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a question is answered on the device through the helper`() = runBlocking<Unit> {
        val script = fake.script(
            listOf(
                probe(),
                inferenceProbe("available", contextSize = 4096),
                inferenceCount(60),
                inferenceGenerate("A floor is the least capable model a call accepts."),
            ),
        )

        withAssistant(script) { assistant ->
            val answer = assertIs<LocalFirstOutcome.Answered>(assistant.ask("What is a rung floor?")).answer

            assertEquals("A floor is the least capable model a call accepts.", answer.text)
            assertEquals(InferenceLocality.ON_DEVICE, answer.locality)
            assertEquals(
                InferenceLocality.ON_DEVICE,
                assistant.classifier.localityOf(answer.providerId, answer.modelId),
            )
            withTimeout(5.seconds) { assistant.state.first { it.servedOnDevice == 1 && !it.isInUse } }

            // The request's parts, apart (AMPR-462): the assistant's prompt as instructions, the
            // question as the prompt, and the session's output budget as the cap.
            val params = fake.requests()
                .single { it["op"]!!.jsonPrimitive.content == AmpereHelperWire.Op.INFERENCE_GENERATE }["params"]!!
                .jsonObject
            assertEquals("What is a rung floor?", params["prompt"]!!.jsonPrimitive.content)
            assertEquals(OnDeviceAssistantAgent.prompt, params["instructions"]!!.jsonPrimitive.content)
            assertEquals(
                OnDeviceInferenceSession.DEFAULT_MAX_OUTPUT_TOKENS,
                params["maxOutputTokens"]!!.jsonPrimitive.content.toInt(),
            )
        }
    }

    @Test
    fun `a device that cannot serve sends nothing and says why`() = runBlocking<Unit> {
        val script = fake.script(
            listOf(probe(), inferenceProbe("appleIntelligenceNotEnabled"), inferenceGenerate("never")),
        )

        withAssistant(script) { assistant ->
            val failed = assertIs<LocalFirstOutcome.Failed>(assistant.ask("Anything?"))

            assertEquals(LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE, failed.reason)
            assertEquals(LocalUnavailableReason.NOT_ENABLED, failed.detail)
            assertEquals("Apple Intelligence is turned off in Settings. Nothing was sent.", failed.describeFailure())
            assertFalse(AmpereHelperWire.Op.INFERENCE_GENERATE in fake.ops())
        }
    }

    @Test
    fun `a crashed helper reads as unavailable with a reason and nothing is sent`() = runBlocking<Unit> {
        withAssistant(fake.exiting(3)) { assistant ->
            val failed = assertIs<LocalFirstOutcome.Failed>(assistant.ask("Anything?"))

            assertEquals(LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE, failed.reason)
            assertEquals(LocalUnavailableReason.HELPER_UNREACHABLE, failed.detail)
            assertEquals("The on-device model helper is not responding. Nothing was sent.", failed.describeFailure())
        }
    }

    @Test
    fun `binding finds the helper and spawns nothing until the first probe`() = runBlocking<Unit> {
        val script = fake.script(listOf(probe(), inferenceProbe("available", contextSize = 4096)))
        val locator = AmpereHelperLocator(
            osName = "Mac OS X",
            property = { if (it == AmpereHelperLocator.HELPER_PROPERTY) script.path else null },
            environment = { null },
            installDirectory = null,
        )

        InMemoryEventDoor.open(agentId = "cli", scope = scope, logger = SilentEventLogger()).use { door ->
            OnDeviceAssistant.bind(door::doorFor, locator).use { assistant ->
                val bound = assertIs<OnDeviceAssistant.Bound>(assistant)
                assertEquals(0, fake.spawns(), "binding starts no process")

                assertTrue(bound.refreshAvailability().available)
                assertEquals(1, fake.spawns())
            }
        }
    }

    @Test
    fun `a host without a helper is unbound, with the reason, and builds no door`() {
        val assistant = OnDeviceAssistant.bind(
            eventApiFactory = { error("an unbound host builds no door") },
            locator = AmpereHelperLocator(osName = "Linux"),
        )

        assertTrue(assertIs<OnDeviceAssistant.Unbound>(assistant).reason.contains("macOS"))
    }

    private suspend fun withAssistant(script: java.io.File, block: suspend (OnDeviceAssistant.Bound) -> Unit) {
        val helper = AmpereHelperProcess(script, probeTimeout = 5.seconds)
        InMemoryEventDoor.open(
            agentId = OnDeviceInferenceSession.AGENT_ID,
            scope = scope,
            logger = SilentEventLogger(),
        ).use { door ->
            OnDeviceAssistant.Bound(
                engine = HelperLocalInferenceEngine(helper),
                eventApi = door.api,
                resources = helper,
            ).use { assistant -> block(assistant) }
        }
    }
}
