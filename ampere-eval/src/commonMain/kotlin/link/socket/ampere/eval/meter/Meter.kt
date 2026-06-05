package link.socket.ampere.eval.meter

import kotlinx.serialization.Serializable
import link.socket.ampere.eval.trace.Trace

@Serializable
data class Reading(
    val score: Double,
    val passed: Boolean,
    val meterId: String,
    val detail: Map<String, String> = emptyMap(),
)

data class Tolerance(val minScore: Double) {
    fun passes(score: Double) = score >= minScore
}

fun interface Meter {
    suspend fun measure(trace: Trace): Result<Reading>
}

sealed class MeterError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class EmptyTrace(meterId: String) : MeterError("[$meterId] trace has no events")
    class NoReadings(meterId: String) : MeterError("[$meterId] no child meters produced a reading")
    class MalformedJudgeResponse(meterId: String, response: String) :
        MeterError("[$meterId] judge response could not be parsed: «$response»")
}
