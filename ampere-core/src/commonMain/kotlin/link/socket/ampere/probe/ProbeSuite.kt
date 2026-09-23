package link.socket.ampere.probe

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Runs an ordered list of Probes over one subject.
 *
 * The caller supplies [evaluate]'s `subjectId` because [S] is unconstrained
 * and the SPI cannot ask the subject for its own identity.
 *
 * Pass an [eventApi] to make the verdicts visible in the trace: [evaluate] then
 * publishes one [ProbeEvent.VerdictReached] per report, in probe order, after
 * every Probe has run. The door persists each verdict to the `EventStore` before
 * dispatching it (F1, AMPR-339). Left null — the default for Bench fixtures and
 * unit tests — evaluation is pure and nothing is published.
 *
 * Wiring is manual, as everywhere else in Ampere: [eventSource], [now], and
 * [idGenerator] are constructor parameters so a test can pin what a published
 * event carries (F13 will revisit whether the door's identity should replace
 * [eventSource]).
 */
class ProbeSuite<in S>(
    private val probes: List<Probe<S>>,
    private val eventApi: AgentEventApi? = null,
    private val eventSource: EventSource = EventSource.Agent(DEFAULT_SOURCE_ID),
    private val now: () -> Instant = { Clock.System.now() },
    private val idGenerator: () -> String = { generateUUID() },
) {

    suspend fun evaluate(subjectId: String, subject: S): List<ProbeReport> {
        val reports = probes.map { probe ->
            ProbeReport(
                probeId = probe.id,
                subjectId = subjectId,
                verdict = probe.evaluate(subject),
            )
        }

        // Published after the whole suite runs, so a subscriber never sees a
        // partial verdict set from a suite that threw halfway through. The door
        // logs a persist failure; the reports are still returned.
        eventApi?.let { api ->
            reports.forEach { report ->
                api.publish(
                    ProbeEvent.VerdictReached(
                        eventId = idGenerator(),
                        eventSource = eventSource,
                        timestamp = now(),
                        probeId = report.probeId,
                        subjectId = report.subjectId,
                        verdict = report.verdict,
                    ),
                )
            }
        }

        return reports
    }

    companion object {
        /** Attribution for verdicts published by a suite the caller did not name. */
        const val DEFAULT_SOURCE_ID: String = "ampere.probe-suite"
    }
}
