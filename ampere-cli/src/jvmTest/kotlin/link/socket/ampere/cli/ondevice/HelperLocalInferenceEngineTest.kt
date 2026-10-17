package link.socket.ampere.cli.ondevice

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceCount
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceGenerate
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceProbe
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.probe
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.refuse
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.silent
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The JVM half of the on-device engine against a fake helper (AMPR-375): the wire, every
 * availability token, the deadline, the restart, the version check, the serialisation and the
 * process-group termination. Nothing here needs, or says anything about, the real model.
 */
class HelperLocalInferenceEngineTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var fake: FakeAmpereHelper
    private val opened = mutableListOf<AmpereHelperProcess>()

    @BeforeEach
    fun setUp() {
        fake = FakeAmpereHelper(directory)
    }

    @AfterEach
    fun tearDown() {
        opened.forEach { it.close() }
    }

    @Test
    fun `an available model reads as the catalog's on-device model with the system's window`() = runBlocking<Unit> {
        val engine = engine(helper(probe(), inferenceProbe("available", contextSize = 4096)))

        val capacity = engine.probe()

        assertTrue(capacity.available)
        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, capacity.modelId)
        assertEquals(4096, capacity.maxContextTokens)
        assertEquals(AIProvider_OnDevice.id, capacity.providerId)
        assertNull(capacity.reason)
    }

    @Test
    fun `each availability token reads as its reason code`() = runBlocking<Unit> {
        val expected = mapOf(
            "deviceNotEligible" to LocalUnavailableReason.DEVICE_NOT_ELIGIBLE,
            "appleIntelligenceNotEnabled" to LocalUnavailableReason.NOT_ENABLED,
            "modelNotReady" to LocalUnavailableReason.MODEL_NOT_READY,
            "osTooOld" to LocalUnavailableReason.OS_TOO_OLD,
            "unavailable" to LocalUnavailableReason.UNAVAILABLE,
            // A case a newer helper adds degrades to "the system said no" rather than breaking.
            "aCaseFromTheFuture" to LocalUnavailableReason.UNAVAILABLE,
        )

        expected.forEach { (token, reason) ->
            val tokenFake = FakeAmpereHelper(Files.createDirectories(directory.resolve(token)))
            val helper = AmpereHelperProcess(tokenFake.script(listOf(probe(), inferenceProbe(token)))).also(opened::add)

            val capacity = HelperLocalInferenceEngine(helper).probe()

            assertFalse(capacity.available, token)
            assertEquals(reason, capacity.reason, token)
            assertEquals(AIProvider_OnDevice.id, capacity.providerId, token)
        }
    }

    @Test
    fun `the helper is spawned in its own process group and its address is held`() = runBlocking<Unit> {
        val helper = helper(probe(), inferenceProbe("available", contextSize = 4096))

        assertTrue(engine(helper).probe().available)

        val address = assertNotNull(helper.address)
        assertEquals(fake.pids().single(), address.leaderPid)
        assertEquals(address.leaderPid, address.processGroupId, "the helper leads a group of its own")
        assertEquals(address, assertIs<AmpereHelperHealth.Ready>(helper.health.value).address)
    }

    @Test
    fun `a generation carries the prompt verbatim and returns the text`() = runBlocking<Unit> {
        val engine = engine(helper(probe(), inferenceGenerate("Rungs are floors.")))
        val prompt = "System: Answer briefly.\n\nUser: What is a \"rung\"? Ten words — no more."

        assertEquals("Rungs are floors.", engine.generate(prompt).getOrThrow())

        val sent = fake.requests().single { it["op"]!!.jsonPrimitive.content == AmpereHelperWire.Op.INFERENCE_GENERATE }
        val params = sent["params"]!!.jsonObject
        assertEquals(prompt, params["prompt"]!!.jsonPrimitive.content)
        assertEquals(AmpereHelperWire.VERSION, sent["v"]!!.jsonPrimitive.int)
        assertNull(params["instructions"], "a part the caller did not set is left out of the frame")
        assertNull(params["maxOutputTokens"])
    }

    @Test
    fun `a request goes out in its parts and is counted as the session it starts`() = runBlocking<Unit> {
        val engine = engine(helper(probe(), inferenceGenerate("Rungs are floors."), inferenceCount(137)))
        val request = LocalGenerationRequest(
            instructions = "Answer briefly.",
            prompt = "What is a rung?",
            maxOutputTokens = 64,
            temperature = 0.2,
        )

        assertEquals("Rungs are floors.", engine.generate(request).getOrThrow())
        assertEquals(137, engine.tokenCount(request))
        assertEquals(137, engine.tokenCount(LocalGenerationRequest(instructions = null, prompt = "Hi.")))

        val (generated, counted, countedBare) = fake.requests().drop(1).map { it["params"]!!.jsonObject }
        assertEquals("What is a rung?", generated["prompt"]!!.jsonPrimitive.content)
        assertEquals("Answer briefly.", generated["instructions"]!!.jsonPrimitive.content)
        assertEquals(64, generated["maxOutputTokens"]!!.jsonPrimitive.int)
        assertEquals(0.2, generated["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals("What is a rung?", counted["prompt"]!!.jsonPrimitive.content)
        assertEquals("Answer briefly.", counted["instructions"]!!.jsonPrimitive.content)
        assertNull(counted["text"], "a request is counted as the session it starts, not as one string")
        assertNull(countedBare["instructions"])
    }

    @Test
    fun `a refused generation fails with the framework's words and keeps the helper`() = runBlocking<Unit> {
        val engine = engine(
            helper(
                probe(),
                refuse(AmpereHelperWire.Op.INFERENCE_GENERATE, "generation_failed", "May contain unsafe content"),
                inferenceProbe("available", contextSize = 4096),
            ),
        )

        val failure = assertIs<AmpereHelperException.Refused>(engine.generate("User: anything").exceptionOrNull())

        assertEquals("May contain unsafe content", failure.message)
        assertEquals(AmpereHelperWire.ErrorCode.GENERATION_FAILED, failure.code)
        assertTrue(engine.probe().available, "a refusal is an answer: the model can still serve")
        assertEquals(1, fake.spawns())
    }

    @Test
    fun `a helper that exits mid-generation fails the call and the next request respawns it`() = runBlocking<Unit> {
        val helper = helper(
            probe(),
            fake.dieOnce(AmpereHelperWire.Op.INFERENCE_GENERATE, """{"text":"second time"}"""),
            inferenceProbe("available", contextSize = 4096),
        )
        val engine = engine(helper)

        val failure = assertIs<AmpereHelperException.Unreachable>(engine.generate("User: first").exceptionOrNull())

        assertTrue(failure.message.orEmpty().contains("exited"), failure.message)
        assertIs<AmpereHelperHealth.Broken>(helper.health.value)
        assertTrue(engine.probe().available, "the next probe starts a fresh helper")
        assertEquals("second time", engine.generate("User: again").getOrThrow())
        assertEquals(2, fake.spawns())
    }

    @Test
    fun `a generation past its deadline is cut and its process group ended`() = runBlocking<Unit> {
        val helper = helper(
            probe(),
            silent(AmpereHelperWire.Op.INFERENCE_GENERATE),
            inferenceProbe("available", contextSize = 4096),
        )
        val engine = engine(helper, generate = 1.seconds)
        assertTrue(engine.probe().available)
        val stalled = assertNotNull(helper.address)

        val failure = assertIs<AmpereHelperException.Unreachable>(engine.generate("User: stall").exceptionOrNull())

        assertTrue(failure.timedOut)
        assertFalse(ProcessGroups.isAlive(stalled), "a stalled generation's helper is not left holding the model")
        assertTrue(engine.probe().available, "the next request starts a fresh helper")
        assertEquals(2, fake.spawns())
    }

    @Test
    fun `a helper too old for this protocol is unavailable with a reason and sent nothing else`() = runBlocking<Unit> {
        val helper = helper(probe(min = 2, max = 3), inferenceProbe("available", contextSize = 4096))
        val engine = engine(helper)

        val capacity = engine.probe()

        assertFalse(capacity.available)
        assertEquals(LocalUnavailableReason.HELPER_INCOMPATIBLE, capacity.reason)
        assertEquals(listOf(AmpereHelperWire.Op.PROBE), fake.ops())
        assertIs<AmpereHelperHealth.Incompatible>(helper.health.value)
        assertTrue(engine.generate("User: anything").isFailure)
        assertEquals(LocalUnavailableReason.HELPER_INCOMPATIBLE, engine.probe().reason)
        assertEquals(1, fake.spawns(), "a helper that cannot speak this version is not respawned")
    }

    @Test
    fun `a helper that does not serve inference is unavailable with a reason`() = runBlocking<Unit> {
        val engine = engine(helper(probe(services = """["mirror"]"""), inferenceProbe("available", contextSize = 4096)))

        assertEquals(LocalUnavailableReason.HELPER_INCOMPATIBLE, engine.probe().reason)
        assertEquals(listOf(AmpereHelperWire.Op.PROBE), fake.ops())
    }

    @Test
    fun `an unsupported protocol refusal makes the helper incompatible`() = runBlocking<Unit> {
        val helper = helper(
            probe(),
            refuse(AmpereHelperWire.Op.INFERENCE_PROBE, "unsupported_protocol", "this helper speaks protocol 2"),
        )

        assertEquals(LocalUnavailableReason.HELPER_INCOMPATIBLE, engine(helper).probe().reason)
        assertIs<AmpereHelperHealth.Incompatible>(helper.health.value)
    }

    @Test
    fun `a helper that cannot start is unavailable with a reason, not a failed call`() = runBlocking<Unit> {
        val helper = AmpereHelperProcess(fake.exiting(3)).also(opened::add)

        val capacity = engine(helper).probe()

        assertFalse(capacity.available)
        assertEquals(LocalUnavailableReason.HELPER_UNREACHABLE, capacity.reason)
        assertIs<AmpereHelperHealth.Broken>(helper.health.value)
    }

    @Test
    fun `the token count is the model's own, and null when it cannot count`() = runBlocking<Unit> {
        assertEquals(42, engine(helper(probe(), inferenceCount(42))).tokenCount("count me"))
        assertEquals("count me", fake.requests().last()["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        val beforeMacOs264 = FakeAmpereHelper(Files.createDirectories(directory.resolve("no-count")))
        val nullHelper = AmpereHelperProcess(beforeMacOs264.script(listOf(probe(), inferenceCount(null))))
            .also(opened::add)
        assertNull(engine(nullHelper).tokenCount("count me"))

        // A helper without the op, such as Socket's: an answer, not a broken helper.
        val older = FakeAmpereHelper(Files.createDirectories(directory.resolve("older")))
        val olderHelper = AmpereHelperProcess(older.script(listOf(probe(), inferenceProbe("available", 4096))))
            .also(opened::add)
        val olderEngine = engine(olderHelper)
        assertNull(olderEngine.tokenCount("count me"))
        assertTrue(olderEngine.probe().available)
        assertEquals(1, older.spawns())
    }

    @Test
    fun `requests are sent one at a time`() = runBlocking<Unit> {
        val engine = engine(helper(probe(), fake.slowReply(AmpereHelperWire.Op.INFERENCE_GENERATE, 1, """{"text":"done"}""")))

        coroutineScope {
            val first = async { engine.generate("User: first") }
            val second = async { engine.generate("User: second") }
            assertEquals("done", first.await().getOrThrow())
            assertEquals("done", second.await().getOrThrow())
        }

        val order = fake.order()
        assertEquals(listOf("recv", "sent", "recv", "sent"), order.map { it.substringBefore(" ") }, order.toString())
        assertEquals(
            order[0].substringAfter(" "),
            order[1].substringAfter(" "),
            "each reply came before the next request",
        )
    }

    @Test
    fun `close ends the helper's whole process group and nothing spawns after it`() = runBlocking<Unit> {
        val helper = helper(probe(), inferenceProbe("available", contextSize = 4096), startChild = true)
        val engine = engine(helper)
        assertTrue(engine.probe().available)
        val address = assertNotNull(helper.address)
        val child = assertNotNull(fake.childPid())

        helper.close()

        assertFalse(ProcessGroups.isAlive(address))
        awaitGone(child)
        assertNull(helper.address)
        assertEquals(LocalUnavailableReason.HELPER_UNREACHABLE, engine.probe().reason)
        assertEquals(1, fake.spawns())
        helper.close()
    }

    private fun helper(vararg cases: String, startChild: Boolean = false): AmpereHelperProcess =
        AmpereHelperProcess(binary = fake.script(cases.toList(), startChild = startChild), probeTimeout = 5.seconds)
            .also(opened::add)

    private fun engine(helper: AmpereHelperProcess, generate: Duration = 10.seconds) =
        HelperLocalInferenceEngine(
            helper = helper,
            timeouts = HelperInferenceTimeouts(probe = 5.seconds, generate = generate, count = 5.seconds),
        )

    private suspend fun awaitGone(pid: Long) {
        withTimeout(5.seconds) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(50)
        }
    }
}
