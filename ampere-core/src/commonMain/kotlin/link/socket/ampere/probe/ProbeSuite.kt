package link.socket.ampere.probe

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Runs an ordered list of Probes over one subject.
 *
 * The caller supplies [evaluate]'s `subjectId` because [S] is unconstrained
 * and the SPI cannot ask the subject for its own identity.
 *
 * Pass an [eventBus] to make the verdicts visible in the trace: [evaluate] then
 * publishes one [ProbeEvent.VerdictReached] per report, in probe order, after
 * every Probe has run. Left null — the default for Bench fixtures and unit
 * tests — evaluation is pure and nothing is published.
 *
 * Wiring is manual, as everywhere else in Ampere: [eventSource], [now], and
 * [idGenerator] are constructor parameters so a test can pin what a published
 * event carries.
 */
class ProbeSuite<in S>(
    private val probes: List<Probe<S>>,
    private val eventBus: EventSerialBus? = null,
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
        // partial verdict set from a suite that threw halfway through.
        eventBus?.let { bus ->
            reports.forEach { report ->
                bus.publish(
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
