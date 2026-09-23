package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource

/**
 * AMPR-321 task 3 validation, no-api half: a suite without a door stays pure. The
 * with-door half — verdicts persisted and dispatched, in probe order — lives in jvmTest
 * as `ProbeSuitePersistenceTest`, where an `AgentEventApi` can be built (F1, AMPR-339).
 */
class ProbeSuiteEventTest {

    private val holdsProbe = object : Probe<String> {
        override val id = ProbeId("holds")

        override suspend fun evaluate(subject: String): Verdict = Verdict.Holds()
    }

    private val violatesProbe = object : Probe<String> {
        override val id = ProbeId("violates")

        override suspend fun evaluate(subject: String): Verdict =
            Verdict.Violated(reason = "cycle: T3 -> T7 -> T3")
    }

    private val undeterminedProbe = object : Probe<String> {
        override val id = ProbeId("undetermined")

        override suspend fun evaluate(subject: String): Verdict = Verdict.Undetermined(
            reason = "T7 publishes no schedule",
            cause = UndeterminedCause.EVIDENCE_ABSENT,
        )
    }

    private val timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `a suite with no door publishes nothing and still reports`() = runTest {
        val reports = ProbeSuite(
            probes = listOf(holdsProbe, violatesProbe, undeterminedProbe),
            eventApi = null,
            eventSource = EventSource.Agent("agent-planner"),
            now = { timestamp },
            idGenerator = { "evt-fixed" },
        ).evaluate(subjectId = "plan-7", subject = "a plan graph")

        assertEquals(3, reports.size)
        assertEquals(
            listOf(ProbeId("holds"), ProbeId("violates"), ProbeId("undetermined")),
            reports.map { it.probeId },
        )
        assertEquals(
            Verdict.Undetermined(
                reason = "T7 publishes no schedule",
                cause = UndeterminedCause.EVIDENCE_ABSENT,
            ),
            reports.last().verdict,
        )
    }
}
