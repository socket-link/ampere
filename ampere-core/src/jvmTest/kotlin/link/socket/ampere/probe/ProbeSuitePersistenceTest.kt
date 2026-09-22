package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.EventHandler

/**
 * AMPR-339 step 5: a suite handed a door persists every verdict to the `EventStore` before
 * dispatching it on the bus (F1), one `VerdictReached` per probe, in probe order. Bodies use
 * `runBlocking`: the door persists on a real IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSuitePersistenceTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

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

    private fun door(): InMemoryEventApi.Handle =
        InMemoryEventApi.open(agentId = ProbeSuite.DEFAULT_SOURCE_ID, scope = doorScope)

    /** The store returns newest first; reversed here so assertions read in publish order. */
    private suspend fun InMemoryEventApi.Handle.storedVerdicts(): List<ProbeEvent.VerdictReached> =
        repository.getEventsByType(ProbeEvent.VerdictReached.EVENT_TYPE).getOrThrow()
            .map { it as ProbeEvent.VerdictReached }
            .asReversed()

    @Test
    fun `evaluate persists one verdict event per probe in probe order`() = runBlocking {
        door().use { door ->
            val received = mutableListOf<ProbeEvent.VerdictReached>()
            door.bus.subscribeSuspending(
                agentId = "oscilloscope",
                eventType = ProbeEvent.VerdictReached.EVENT_TYPE,
                handler = EventHandler { event, _ -> received += event as ProbeEvent.VerdictReached },
            )

            var next = 0
            val reports = ProbeSuite(
                probes = listOf(holdsProbe, violatesProbe, undeterminedProbe),
                eventApi = door.api,
                eventSource = EventSource.Agent("agent-planner"),
                now = { timestamp },
                idGenerator = { "evt-${next++}" },
            ).evaluate(subjectId = "plan-7", subject = "a plan graph")

            val stored = door.storedVerdicts()
            assertEquals(3, stored.size)
            assertEquals(
                listOf(ProbeId("holds"), ProbeId("violates"), ProbeId("undetermined")),
                stored.map { it.probeId },
            )
            assertEquals(reports.map { it.verdict }, stored.map { it.verdict })
            assertTrue(stored.all { it.subjectId == "plan-7" })
            assertTrue(stored.all { it.timestamp == timestamp })
            assertEquals(EventSource.Agent("agent-planner"), stored.first().eventSource)
            assertEquals(listOf("evt-0", "evt-1", "evt-2"), stored.map { it.eventId })

            // The bus sees exactly what the store sees.
            assertEquals(stored.map { it.eventId }, received.map { it.eventId })
        }
    }

    @Test
    fun `an undetermined verdict is persisted as itself and not as a pass`() = runBlocking {
        door().use { door ->
            ProbeSuite(probes = listOf(undeterminedProbe), eventApi = door.api)
                .evaluate(subjectId = "plan-7", subject = "a plan graph")

            assertEquals(
                Verdict.Undetermined(
                    reason = "T7 publishes no schedule",
                    cause = UndeterminedCause.EVIDENCE_ABSENT,
                ),
                door.storedVerdicts().single().verdict,
            )
        }
    }
}
