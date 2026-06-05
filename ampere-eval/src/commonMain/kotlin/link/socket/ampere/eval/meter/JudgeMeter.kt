package link.socket.ampere.eval.meter

import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.eval.trace.Trace

/**
 * Performs an LLM completion call (prompt → text) on behalf of a [JudgeMeter].
 *
 * In production this wraps the live provider client. In CI a stubbed lambda returns
 * a fixed response, and routing is served by a [link.socket.ampere.eval.relay.PlaybackRelay]
 * wired as the [JudgeMeter.relay] — making the judge deterministic under replay.
 */
fun interface JudgeClient {
    suspend fun call(prompt: String): Result<String>
}

/**
 * LLM-grades a [Trace] against a [rubric], parsing a normalized score from the response.
 *
 * The [relay] resolves routing (which model to call) — swapping in a
 * [link.socket.ampere.eval.relay.PlaybackRelay] makes the judge deterministic in CI.
 * The [client] performs the actual completion; for tests inject a stubbed lambda.
 *
 * The judge response must contain `"Score: X.X"` (case-insensitive) where `X.X` is
 * a value in `0.0..1.0`. Anything else is a [MeterError.MalformedJudgeResponse].
 */
class JudgeMeter(
    val meterId: String,
    private val tolerance: Tolerance,
    private val rubric: String,
    val relay: CognitiveRelay,
    private val client: JudgeClient,
) : Meter {

    override suspend fun measure(trace: Trace): Result<Reading> {
        if (trace.events.isEmpty()) {
            return Result.failure(MeterError.EmptyTrace(meterId))
        }
        val prompt = buildPrompt(trace)
        return client.call(prompt).fold(
            onSuccess = { response ->
                val score = parseScore(response)
                    ?: return Result.failure(MeterError.MalformedJudgeResponse(meterId, response))
                Result.success(
                    Reading(score = score, passed = tolerance.passes(score), meterId = meterId),
                )
            },
            onFailure = { Result.failure(it) },
        )
    }

    private fun buildPrompt(trace: Trace): String {
        val eventLines = trace.events.joinToString("\n") { "  [${it.index}] ${it.type} at ${it.timestamp}" }
        return """Grade the trajectory below against the rubric.
Respond with exactly: "Score: X.X" (0.0 to 1.0) then your reasoning.

Rubric: $rubric

Trajectory (${trace.events.size} events):
$eventLines"""
    }

    companion object {
        private val SCORE_REGEX = Regex("""(?i)score:\s*(\d+(?:\.\d+)?)""")

        fun parseScore(response: String): Double? =
            SCORE_REGEX.find(response)
                ?.groupValues?.get(1)
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..1.0 }
    }
}
