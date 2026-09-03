package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict

/**
 * Serialization samples for [ProbeEvent.VerdictReached] (AMPR-321 task 2).
 *
 * A verdict is only useful in a trace if it survives the round-trip whole:
 * `subjectId` says *what* was judged, and the [Verdict] subtype says how. Both
 * are pinned here, across all four verdict values.
 */
class ProbeEventTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun verdictReached(
        verdict: Verdict,
        subjectId: String = "plan-7",
        detail: Map<String, String> = emptyMap(),
    ): ProbeEvent.VerdictReached = ProbeEvent.VerdictReached(
        eventId = "11111111-1111-1111-1111-111111111111",
        eventSource = EventSource.Agent("agent-planner"),
        timestamp = timestamp,
        probeId = ProbeId("ampere.sequence"),
        subjectId = subjectId,
        verdict = verdict,
        detail = detail,
    )

    private fun verdictSamples(): List<Verdict> = listOf(
        Verdict.Holds(),
        Verdict.Warn(reason = "T3 is scheduled tight against T7"),
        Verdict.Violated(reason = "cycle: T3 -> T7 -> T3"),
        Verdict.Undetermined(
            reason = "no schedule on T7",
            cause = UndeterminedCause.EVIDENCE_ABSENT,
        ),
    )

    @Test
    fun `every verdict survives the polymorphic Event round-trip`() {
        verdictSamples().forEach { verdict ->
            val original: Event = verdictReached(verdict)

            val encoded = json.encodeToString(Event.serializer(), original)
            val decoded = json.decodeFromString(Event.serializer(), encoded)

            assertIs<ProbeEvent.VerdictReached>(decoded)
            assertEquals("plan-7", decoded.subjectId, "subjectId was lost for $verdict")
            assertEquals(verdict, decoded.verdict, "verdict changed shape")
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `detail round-trips and defaults to empty`() {
        val withDetail = verdictReached(
            verdict = Verdict.Violated(reason = "cycle"),
            detail = mapOf("edge" to "T3 -> T7"),
        )

        val decoded = json.decodeFromString(
            Event.serializer(),
            json.encodeToString(Event.serializer(), withDetail),
        )

        assertIs<ProbeEvent.VerdictReached>(decoded)
        assertEquals(mapOf("edge" to "T3 -> T7"), decoded.detail)
        assertEquals(emptyMap(), verdictReached(Verdict.Holds()).detail)
    }

    /**
     * Both names are frozen the moment a trace is written: the class name is the
     * polymorphic discriminator a recorded event decodes through, and `EVENT_TYPE`
     * is what subscribers and [EventRegistry] address. Renaming either makes older
     * traces undecodable — the reason `BenchEvent.ProbeGraded` still has its name.
     */
    @Test
    fun `the class discriminator and event type are pinned`() {
        val encoded = json.encodeToString(Event.serializer(), verdictReached(Verdict.Holds()))

        assertTrue(
            encoded.contains("ProbeEvent.VerdictReached"),
            "expected the pinned class discriminator; got $encoded",
        )
        assertEquals("VerdictReached", ProbeEvent.VerdictReached.EVENT_TYPE)
        assertTrue(ProbeEvent.VerdictReached.EVENT_TYPE in EventRegistry.allEventTypes)
    }

    @Test
    fun `an undetermined verdict never reads as a pass in the summary`() {
        val summary = verdictReached(
            verdict = Verdict.Undetermined(
                reason = "no schedule on T7",
                cause = UndeterminedCause.EVIDENCE_ABSENT,
            ),
        ).getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { it.getIdentifier() },
        )

        assertTrue(summary.contains("undetermined(EVIDENCE_ABSENT)"), summary)
        assertTrue(summary.contains("plan-7"), summary)
        assertTrue(!summary.contains("holds"), summary)
    }

    @Test
    fun `urgency defaults to low`() {
        assertEquals(Urgency.LOW, verdictReached(Verdict.Violated(reason = "cycle")).urgency)
    }
}
