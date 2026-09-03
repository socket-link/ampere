package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.probe.Probe
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.ProbeSuite
import link.socket.ampere.probe.Verdict

/**
 * Verdicts reached by a [Probe], carried on the `EventSerialBus` so a decision
 * about a plan, a manifest, or a recalled fact is visible in the trace rather
 * than only returned to whoever asked (AMPR-321).
 *
 * Lives alongside [BenchEvent] for the same reason it does: `Event` is a sealed
 * interface, and Kotlin requires sealed subtypes to share both module and
 * package with the base type. That constraint is also why the payload is
 * primitives plus [Verdict] — a consumer's Probe may judge a subject type
 * Ampere cannot name, so the subject itself never crosses this boundary.
 */
@Serializable
sealed interface ProbeEvent : Event {

    /** The Probe that reached the verdict. */
    val probeId: ProbeId

    /**
     * One Probe reached a verdict on one identified subject.
     *
     * [subjectId] is caller-supplied (see [ProbeSuite]) because a Probe's
     * subject type is unconstrained and the SPI cannot ask a subject for its
     * own identity. [detail] is free-form key/value for the Oscilloscope; keep
     * it small — it is stored in every trace that captures this event.
     */
    @Serializable
    data class VerdictReached(
        override val eventId: EventId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val probeId: ProbeId,
        val subjectId: String,
        val verdict: Verdict,
        val detail: Map<String, String> = emptyMap(),
        override val urgency: Urgency = Urgency.LOW,
    ) : ProbeEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Probe ${probeId.value} on $subjectId: ${verdict.label()}")
            verdict.reason?.let { append(" — $it") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "VerdictReached"
        }
    }
}

/**
 * Short, stable name for a [Verdict] in a summary line. `Undetermined` reads as
 * itself and never as a soft pass — the whole point of the fourth value.
 */
private fun Verdict.label(): String = when (this) {
    is Verdict.Holds -> "holds"
    is Verdict.Warn -> "warn"
    is Verdict.Violated -> "violated"
    is Verdict.Undetermined -> "undetermined(${cause.name})"
}
