package link.socket.ampere.agents.domain.routing.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.emission.DangerLevel
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.ProseFormat

/**
 * Structured-output round-trip (AMPR-225): a genuine guided-generation engine
 * hands back an [EmissionPayload] directly — no string parsing between model
 * output and typed Emission — while an engine that only implements the
 * text-shaped [LocalInferenceEngine.generate] falls back to the interface's
 * safe default.
 */
class LocalInferenceEngineStructuredTest {

    @Test
    fun `default generateStructured wraps plain text as Prose without invoking any parser`() = runTest {
        val engine = FakeLocalInferenceEngine(respond = { Result.success("Hello from on-device.") })

        val result = engine.generateStructured(EmissionKind.Prose, "Say hello")

        val payload = assertIs<EmissionPayload.Prose>(result.getOrThrow())
        assertEquals("Hello from on-device.", payload.text)
        assertEquals(ProseFormat.PLAIN, payload.format)
        assertEquals(1, engine.generateCount, "default path must go through generate(), not a separate parser")
        assertEquals(0, engine.structuredGenerateCount, "no scripted structured responder was supplied")
    }

    @Test
    fun `default generateStructured fails for non-Prose kinds rather than guessing structure`() = runTest {
        val engine = FakeLocalInferenceEngine(respond = { Result.success("action: delete file") })

        val result = engine.generateStructured(EmissionKind.Confirmation, "Confirm the delete")

        assertTrue(result.isFailure, "a text-only engine must not fabricate a Confirmation payload")
        assertEquals(0, engine.generateCount, "must fail before ever calling generate()")
    }

    @Test
    fun `an engine with real guided generation returns the typed payload directly`() = runTest {
        val engine = FakeLocalInferenceEngine(
            respondStructured = { kind, _ ->
                when (kind) {
                    EmissionKind.Confirmation ->
                        Result.success(
                            EmissionPayload.Confirmation(
                                action = "delete build/",
                                preview = "Removes 1 directory",
                                dangerLevel = DangerLevel.LOW,
                            ),
                        )
                    else -> Result.failure(UnsupportedOperationException("unscripted"))
                }
            },
        )

        val result = engine.generateStructured(EmissionKind.Confirmation, "Confirm the delete")

        val payload = assertIs<EmissionPayload.Confirmation>(result.getOrThrow())
        assertEquals("delete build/", payload.action)
        assertEquals(DangerLevel.LOW, payload.dangerLevel)
        assertEquals(EmissionKind.Confirmation, engine.lastRequestedKind)
        assertEquals(1, engine.structuredGenerateCount)
        // The typed payload came from guided generation directly, never from
        // parsing a plain-text generate() response.
        assertEquals(0, engine.generateCount)
    }
}
