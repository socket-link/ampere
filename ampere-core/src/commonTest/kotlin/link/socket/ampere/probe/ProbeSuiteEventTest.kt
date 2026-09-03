package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus

/**
 * AMPR-321 task 3 validation: a suite handed a bus makes its verdicts visible in
 * the trace, and a suite without one stays pure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    private fun suite(bus: EventSerialBus?): ProbeSuite<String> = ProbeSuite(
        probes = listOf(holdsProbe, violatesProbe, undeterminedProbe),
        eventBus = bus,
        eventSource = EventSource.Agent("agent-planner"),
        now = { timestamp },
        idGenerator = { "evt-fixed" },
    )

    @Test
    fun `evaluate publishes one verdict event per probe in probe order`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<ProbeEvent.VerdictReached>()
        bus.subscribeSuspending(
            agentId = "oscilloscope",
            eventType = ProbeEvent.VerdictReached.EVENT_TYPE,
            handler = EventHandler { event, _ -> received += event as ProbeEvent.VerdictReached },
        )

        val reports = suite(bus).evaluate(subjectId = "plan-7", subject = "a plan graph")
        runCurrent()

        assertEquals(3, received.size)
        assertEquals(
            listOf(ProbeId("holds"), ProbeId("violates"), ProbeId("undetermined")),
            received.map { it.probeId },
        )
        assertEquals(reports.map { it.verdict }, received.map { it.verdict })
        assertTrue(received.all { it.subjectId == "plan-7" })
        assertTrue(received.all { it.timestamp == timestamp })
        assertEquals(EventSource.Agent("agent-planner"), received.first().eventSource)
    }

    @Test
    fun `a suite with no bus publishes nothing and still reports`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<ProbeEvent.VerdictReached>()
        bus.subscribeSuspending(
            agentId = "oscilloscope",
            eventType = ProbeEvent.VerdictReached.EVENT_TYPE,
            handler = EventHandler { event, _ -> received += event as ProbeEvent.VerdictReached },
        )

        val reports = suite(bus = null).evaluate(subjectId = "plan-7", subject = "a plan graph")
        runCurrent()

        assertEquals(3, reports.size)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `an undetermined verdict is published as itself and not as a pass`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<ProbeEvent.VerdictReached>()
        bus.subscribeSuspending(
            agentId = "oscilloscope",
            eventType = ProbeEvent.VerdictReached.EVENT_TYPE,
            handler = EventHandler { event, _ -> received += event as ProbeEvent.VerdictReached },
        )

        ProbeSuite(probes = listOf(undeterminedProbe), eventBus = bus)
            .evaluate(subjectId = "plan-7", subject = "a plan graph")
        runCurrent()

        assertEquals(
            Verdict.Undetermined(
                reason = "T7 publishes no schedule",
                cause = UndeterminedCause.EVIDENCE_ABSENT,
            ),
            received.single().verdict,
        )
    }

    @Test
    fun `each published verdict carries its own event id`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val received = mutableListOf<ProbeEvent.VerdictReached>()
        bus.subscribeSuspending(
            agentId = "oscilloscope",
            eventType = ProbeEvent.VerdictReached.EVENT_TYPE,
            handler = EventHandler { event, _ -> received += event as ProbeEvent.VerdictReached },
        )

        var next = 0
        ProbeSuite(
            probes = listOf(holdsProbe, violatesProbe),
            eventBus = bus,
            idGenerator = { "evt-${next++}" },
        ).evaluate(subjectId = "plan-7", subject = "a plan graph")
        runCurrent()

        assertEquals(listOf("evt-0", "evt-1"), received.map { it.eventId })
    }
}
