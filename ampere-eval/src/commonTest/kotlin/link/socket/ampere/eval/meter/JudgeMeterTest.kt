package link.socket.ampere.eval.meter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.eval.relay.PlaybackRelay
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/** AMPR-185 task 3.4 validation. */
class JudgeMeterTest {

    private val fallback: AIConfiguration =
        AIConfiguration_Default(provider = AIProvider_Anthropic, model = AIModel_Claude.Sonnet_4)

    @Test
    fun `parses score from stubbed judge response via PlaybackRelay`() = runTest {
        val relay = PlaybackRelay(emptyTrace())
        val meter = JudgeMeter(
            meterId = "judge",
            tolerance = Tolerance(0.5),
            rubric = "Did the agent complete the task?",
            relay = relay,
            client = JudgeClient { _ -> Result.success("Score: 0.8\nThe agent performed well.") },
        )
        val reading = meter.measure(oneEventTrace()).getOrThrow()

        assertEquals(0.8, reading.score)
        assertTrue(reading.passed)
        assertEquals("judge", reading.meterId)
    }

    @Test
    fun `score below tolerance fails`() = runTest {
        val relay = PlaybackRelay(emptyTrace())
        val meter = JudgeMeter(
            meterId = "judge",
            tolerance = Tolerance(0.9),
            rubric = "rubric",
            relay = relay,
            client = JudgeClient { _ -> Result.success("Score: 0.5") },
        )
        val reading = meter.measure(oneEventTrace()).getOrThrow()

        assertFalse(reading.passed)
    }

    @Test
    fun `malformed judge output returns Result failure`() = runTest {
        val relay = PlaybackRelay(emptyTrace())
        val meter = JudgeMeter(
            meterId = "judge",
            tolerance = Tolerance(0.5),
            rubric = "rubric",
            relay = relay,
            client = JudgeClient { _ -> Result.success("I give it a solid thumbs up") },
        )
        val result = meter.measure(oneEventTrace())

        assertTrue(result.isFailure)
        assertIs<MeterError.MalformedJudgeResponse>(result.exceptionOrNull())
    }

    @Test
    fun `out-of-range score is treated as malformed`() = runTest {
        val relay = PlaybackRelay(emptyTrace())
        val meter = JudgeMeter(
            meterId = "judge",
            tolerance = Tolerance(0.5),
            rubric = "rubric",
            relay = relay,
            client = JudgeClient { _ -> Result.success("Score: 1.5") },
        )
        val result = meter.measure(oneEventTrace())

        assertTrue(result.isFailure)
        assertIs<MeterError.MalformedJudgeResponse>(result.exceptionOrNull())
    }

    @Test
    fun `empty trace returns typed failure before calling judge`() = runTest {
        var judgeWasCalled = false
        val relay = PlaybackRelay(emptyTrace())
        val meter = JudgeMeter(
            meterId = "judge",
            tolerance = Tolerance(0.5),
            rubric = "rubric",
            relay = relay,
            client = JudgeClient { _ -> judgeWasCalled = true; Result.success("Score: 0.5") },
        )
        val result = meter.measure(emptyTrace())

        assertTrue(result.isFailure)
        assertIs<MeterError.EmptyTrace>(result.exceptionOrNull())
        assertFalse(judgeWasCalled)
    }

    @Test
    fun `client failure propagates as Result failure`() = runTest {
        val boom = RuntimeException("provider error")
        val relay = PlaybackRelay(emptyTrace())
        val meter = JudgeMeter(
            meterId = "judge",
            tolerance = Tolerance(0.5),
            rubric = "rubric",
            relay = relay,
            client = JudgeClient { _ -> Result.failure(boom) },
        )
        val result = meter.measure(oneEventTrace())

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    // region — parseScore unit tests

    @Test
    fun `parseScore extracts normalized score from standard format`() {
        assertEquals(0.8, JudgeMeter.parseScore("Score: 0.8"))
        assertEquals(1.0, JudgeMeter.parseScore("Score: 1.0"))
        assertEquals(0.0, JudgeMeter.parseScore("Score: 0.0"))
        assertEquals(0.75, JudgeMeter.parseScore("Score: 0.75\nReasoning: good job"))
    }

    @Test
    fun `parseScore is case insensitive`() {
        assertEquals(0.5, JudgeMeter.parseScore("SCORE: 0.5"))
        assertEquals(0.5, JudgeMeter.parseScore("score: 0.5"))
    }

    @Test
    fun `parseScore returns null when no score present`() {
        assertEquals(null, JudgeMeter.parseScore("The agent did well."))
        assertEquals(null, JudgeMeter.parseScore("Score: 1.5"))
        assertEquals(null, JudgeMeter.parseScore(""))
    }

    // endregion

    // region — fixtures

    private fun emptyTrace() = Trace(id = "t", runId = "r", arcId = "a", createdAt = 0L, events = emptyList())

    private fun oneEventTrace() = Trace(
        id = "t",
        runId = "r",
        arcId = "a",
        createdAt = 0L,
        events = listOf(
            TraceEvent(index = 0, timestamp = 1L, type = "TaskCompleted", payload = buildJsonObject {}),
        ),
    )

    // endregion
}
