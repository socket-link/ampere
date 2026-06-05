package link.socket.ampere.eval.meter

import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/**
 * Scores a [Trace] by inspecting its terminal event against a [predicate].
 *
 * Score is `1.0` on a match, `0.0` on a mismatch; [tolerance] determines pass/fail.
 * A mismatch surfaces the terminal event type in [Reading.detail].
 */
class OutcomeMeter(
    val meterId: String,
    private val tolerance: Tolerance,
    private val predicate: (TraceEvent) -> Boolean,
) : Meter {

    override suspend fun measure(trace: Trace): Result<Reading> {
        if (trace.events.isEmpty()) {
            return Result.failure(MeterError.EmptyTrace(meterId))
        }
        val terminal = trace.events.last()
        val matched = predicate(terminal)
        val score = if (matched) 1.0 else 0.0
        val detail = if (!matched) {
            mapOf("terminal_type" to terminal.type, "match" to "false")
        } else {
            emptyMap()
        }
        return Result.success(
            Reading(score = score, passed = tolerance.passes(score), meterId = meterId, detail = detail),
        )
    }
}
