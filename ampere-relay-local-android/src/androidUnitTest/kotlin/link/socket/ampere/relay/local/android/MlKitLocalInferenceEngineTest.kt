package link.socket.ampere.relay.local.android

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

class MlKitLocalInferenceEngineTest {

    @Test
    fun `an available model reports the documented window under the on-device provider`() = runTest {
        val capacity = MlKitLocalInferenceEngine(FakeNanoModel(status = NanoStatus.AVAILABLE)).probe()

        assertEquals(
            LocalCapacity(
                available = true,
                modelId = MlKitLocalInferenceEngine.MODEL_ID,
                maxContextTokens = 4_000,
                providerId = AIProvider_OnDevice.id,
            ),
            capacity,
        )
    }

    @Test
    fun `each status that cannot serve reports its own reason`() = runTest {
        val reasons = mapOf(
            NanoStatus.DOWNLOADABLE to NanoUnavailableReason.NOT_DOWNLOADED,
            NanoStatus.DOWNLOADING to NanoUnavailableReason.DOWNLOADING,
            NanoStatus.UNAVAILABLE to NanoUnavailableReason.UNAVAILABLE,
        )

        reasons.forEach { (status, reason) ->
            val capacity = MlKitLocalInferenceEngine(FakeNanoModel(status = status)).probe()

            assertFalse(capacity.available, "$status")
            assertEquals(reason, capacity.reason, "$status")
            assertEquals(AIProvider_OnDevice.id, capacity.providerId, "$status")
            assertNull(capacity.maxContextTokens, "$status")
        }
    }

    @Test
    fun `a status query that throws reports the model unavailable rather than throwing`() = runTest {
        val model = FakeNanoModel(statusFailure = IllegalStateException("service not bound"))

        val capacity = MlKitLocalInferenceEngine(model).probe()

        assertFalse(capacity.available)
        assertEquals(NanoUnavailableReason.STATUS_FAILED, capacity.reason)
    }

    @Test
    fun `instructions go to the model as a system instruction when it takes one`() = runTest {
        val model = FakeNanoModel(systemPrompt = true)

        MlKitLocalInferenceEngine(model).generate(request(instructions = "Answer briefly."))

        assertEquals(
            NanoRequest(
                systemInstruction = "Answer briefly.",
                prompt = "What changed?",
                maxOutputTokens = 256,
                temperature = 0.3f,
            ),
            model.generated.single(),
        )
    }

    @Test
    fun `instructions are folded into the prompt when the model takes no system instruction`() = runTest {
        val model = FakeNanoModel(systemPrompt = false)
        val request = request(instructions = "Answer briefly.")

        MlKitLocalInferenceEngine(model).generate(request)

        val sent = model.generated.single()
        assertNull(sent.systemInstruction)
        assertEquals(request.flattenedPrompt, sent.prompt)
        assertEquals(256, sent.maxOutputTokens)
    }

    @Test
    fun `a model that cannot say whether it takes a system instruction gets it folded`() = runTest {
        val model = FakeNanoModel(systemPromptFailure = IllegalStateException("unknown"))
        val request = request(instructions = "Answer briefly.")

        MlKitLocalInferenceEngine(model).generate(request)

        assertEquals(request.flattenedPrompt, model.generated.single().prompt)
    }

    @Test
    fun `a request without instructions sends the prompt alone`() = runTest {
        val model = FakeNanoModel(systemPrompt = true)

        MlKitLocalInferenceEngine(model).generate(request(instructions = null))

        assertEquals(
            NanoRequest(systemInstruction = null, prompt = "What changed?", maxOutputTokens = 256, temperature = 0.3f),
            model.generated.single(),
        )
    }

    @Test
    fun `a cap that is not positive is sent as no cap and the temperature is kept between 0 and 1`() = runTest {
        val model = FakeNanoModel()
        val engine = MlKitLocalInferenceEngine(model)

        engine.generate(LocalGenerationRequest(null, prompt = "p", maxOutputTokens = 0, temperature = 1.5))
        engine.generate(LocalGenerationRequest(null, prompt = "p", maxOutputTokens = -4, temperature = -0.5))
        engine.generate(LocalGenerationRequest(null, prompt = "p"))

        assertEquals(listOf(null, null, null), model.generated.map { it.maxOutputTokens })
        assertEquals(listOf(1.0f, 0.0f, null), model.generated.map { it.temperature })
    }

    @Test
    fun `the bare prompt form sends the prompt with no options`() = runTest {
        val model = FakeNanoModel(systemPrompt = true)

        val reply = MlKitLocalInferenceEngine(model).generate("Summarise this.")

        assertEquals("reply", reply.getOrThrow())
        assertEquals(NanoRequest(systemInstruction = null, prompt = "Summarise this."), model.generated.single())
    }

    @Test
    fun `a reply cut short at the cap is a reply`() = runTest {
        val model = FakeNanoModel(reply = NanoReply(text = "1, 2, 3", stoppedAtCap = true))

        assertEquals("1, 2, 3", MlKitLocalInferenceEngine(model).generate(request()).getOrThrow())
    }

    @Test
    fun `a reply with no candidate is a failure`() = runTest {
        val model = FakeNanoModel(reply = NanoReply(text = null))

        assertTrue(MlKitLocalInferenceEngine(model).generate(request()).isFailure)
    }

    @Test
    fun `the model's own failure is surfaced unchanged and never retried`() = runTest {
        val busy = IllegalStateException("BUSY")
        val model = FakeNanoModel(generateFailure = busy)

        val result = MlKitLocalInferenceEngine(model).generate(request())

        assertSame(busy, result.exceptionOrNull())
        assertEquals(1, model.generated.size)
    }

    @Test
    fun `cancellation propagates rather than becoming a failed generation`() = runTest {
        val model = FakeNanoModel(generateFailure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> { MlKitLocalInferenceEngine(model).generate(request()) }
    }

    @Test
    fun `a request is counted exactly as it would be sent`() = runTest {
        val model = FakeNanoModel(systemPrompt = true, tokens = 137)
        val engine = MlKitLocalInferenceEngine(model)
        val request = request(instructions = "Answer briefly.")

        assertEquals(137, engine.tokenCount(request))
        engine.generate(request)

        assertEquals(model.generated.single(), model.counted.single())
    }

    @Test
    fun `text is counted as a prompt with no instruction`() = runTest {
        val model = FakeNanoModel(tokens = 12)

        assertEquals(12, MlKitLocalInferenceEngine(model).tokenCount("2026-10-30T12:00:00Z"))
        assertEquals(NanoRequest(systemInstruction = null, prompt = "2026-10-30T12:00:00Z"), model.counted.single())
    }

    @Test
    fun `a count that fails is no count`() = runTest {
        val model = FakeNanoModel(countFailure = IllegalStateException("not available"))
        val engine = MlKitLocalInferenceEngine(model)

        assertNull(engine.tokenCount("text"))
        assertNull(engine.tokenCount(request()))
    }

    @Test
    fun `closing the engine closes the model`() {
        val model = FakeNanoModel()

        MlKitLocalInferenceEngine(model).close()

        assertTrue(model.closed)
    }

    private fun request(instructions: String? = "Answer briefly."): LocalGenerationRequest =
        LocalGenerationRequest(
            instructions = instructions,
            prompt = "What changed?",
            maxOutputTokens = 256,
            temperature = 0.3,
        )

    private class FakeNanoModel(
        private val status: NanoStatus = NanoStatus.AVAILABLE,
        private val statusFailure: Throwable? = null,
        private val systemPrompt: Boolean = true,
        private val systemPromptFailure: Throwable? = null,
        private val reply: NanoReply = NanoReply(text = "reply"),
        private val generateFailure: Throwable? = null,
        private val tokens: Int = 0,
        private val countFailure: Throwable? = null,
    ) : NanoModel {
        val generated = mutableListOf<NanoRequest>()
        val counted = mutableListOf<NanoRequest>()
        var closed = false

        override suspend fun status(): NanoStatus = statusFailure?.let { throw it } ?: status

        override suspend fun systemPromptAvailable(): Boolean = systemPromptFailure?.let { throw it } ?: systemPrompt

        override suspend fun generate(request: NanoRequest): NanoReply {
            generated += request
            generateFailure?.let { throw it }
            return reply
        }

        override suspend fun countTokens(request: NanoRequest): Int {
            counted += request
            countFailure?.let { throw it }
            return tokens
        }

        override fun close() {
            closed = true
        }
    }
}
