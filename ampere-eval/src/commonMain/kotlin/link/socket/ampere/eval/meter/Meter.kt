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

    /**
     * A [TraceConformanceMeter] was given a reference with nothing in it. Distinct from
     * [EmptyTrace]: the graded run is fine and the *suite* is misconfigured — an empty golden
     * trace would pass every run that recorded nothing, which is the failure mode worth naming.
     */
    class EmptyReferenceTrace(meterId: String, referenceId: String) :
        MeterError("[$meterId] reference trace '$referenceId' has no events")

    class MalformedJudgeResponse(meterId: String, response: String) :
        MeterError("[$meterId] judge response could not be parsed: «$response»")
}
