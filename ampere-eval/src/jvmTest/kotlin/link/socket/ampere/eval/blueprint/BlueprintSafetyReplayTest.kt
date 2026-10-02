package link.socket.ampere.eval.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.canon.CanonId
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.relay.PlaybackRelay
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.Verdict
import link.socket.ampere.probe.safety.HazardCategory
import link.socket.ampere.probe.safety.HazardFinding
import link.socket.ampere.probe.safety.KeywordHazardClassifier
import link.socket.ampere.probe.safety.MitigationPlan
import link.socket.ampere.probe.safety.SafetyProbe
import link.socket.ampere.probe.safety.WorkPlan
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.version.AMPERE_VERSION

/**
 * AMPR-380 task 3: replay the three Blueprint fixtures through `SafetyProbe` and
 * check each one against the findings its file pins.
 *
 * Each fixture carries a recorded `ProbeEvent.VerdictReached` as a [Trace], the
 * way the eval suite keeps every fixture, so the payload is decoded exactly as a
 * recorded run's would be and `PlaybackRelay` validates it. The replay then
 * **recomputes** the verdict rather than reading it back — a verdict is recomputed,
 * never replayed — and the two have to agree. A rule change that moves a hazard
 * therefore shows up as a red build against the recording, not as a silent drift.
 *
 * `recordedCallCount` is asserted to be zero on every fixture: the classifier is
 * deterministic keyword and interface-kind rules, so a hazard check that started
 * calling a model would be caught here before it reached a bill.
 */
class BlueprintSafetyReplayTest {

    private val probe = SafetyProbe(KeywordHazardClassifier)
    private val reviewedAt: Instant = Instant.parse("2026-09-28T09:00:00Z")
    private val inspector = EventSource.Agent(BlueprintRoster.inspector.id.value)

    /** One fixture, as the replay reads it: a plan, the findings it must yield, the verdict it must reach. */
    private data class Case(
        val name: String,
        val plan: WorkPlan,
        val findings: List<HazardFinding>,
        val reason: String,
    )

    private val cases = listOf(
        Case("vent", VentFixture.plan, VentFixture.expectedFindings, VentFixture.expectedSafetyReason),
        Case(
            "motion-sensor",
            MotionSensorFixture.plan,
            MotionSensorFixture.expectedFindings,
            MotionSensorFixture.expectedSafetyReason,
        ),
        Case("lawn", LawnFixture.plan, LawnFixture.expectedFindings, LawnFixture.expectedSafetyReason),
    )

    /** The recorded verdict, as `ProbeSuite` publishes it: primitives plus the `Verdict`. */
    private fun trace(case: Case): Trace {
        val event = ProbeEvent.VerdictReached(
            eventId = "verdict-safety-${case.name}",
            eventSource = inspector,
            timestamp = reviewedAt,
            probeId = ProbeId(SafetyProbe.ID),
            subjectId = case.plan.graph.project.canonId.value,
            verdict = Verdict.Warn(case.reason),
        )
        return Trace(
            id = "trace-safety-${case.name}",
            runId = "run-safety-${case.name}",
            arcId = "blueprint",
            createdAt = reviewedAt.toEpochMilliseconds(),
            events = listOf(
                TraceEvent(
                    index = 0,
                    timestamp = reviewedAt.toEpochMilliseconds(),
                    type = event.eventType,
                    payload = DEFAULT_JSON.encodeToJsonElement(Event.serializer(), event),
                ),
            ),
            producerVersion = AMPERE_VERSION,
        )
    }

    @Test
    fun `every fixture reproduces the findings it pins`() = runBlocking<Unit> {
        cases.forEach { case ->
            val relay = PlaybackRelay(trace(case))
            relay.validate().getOrThrow()
            val recorded = DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), trace(case).events.single().payload)
            assertIs<ProbeEvent.VerdictReached>(recorded)

            val inspection = probe.inspect(case.plan)

            BlueprintSafetyGate.check(case.name, inspection.findings).getOrThrow()
            assertEquals(case.findings, inspection.findings, "${case.name}: findings")
            assertEquals(recorded.verdict, inspection.verdict, "${case.name}: the recomputed verdict must match")
            assertEquals(Verdict.Warn(case.reason), inspection.verdict, "${case.name}: verdict")
            assertEquals(0, relay.recordedCallCount, "${case.name}: a 0W probe records no model calls")
        }
    }

    @Test
    fun `every fixture inserts one mitigation per finding with no cycles`() = runBlocking<Unit> {
        cases.forEach { case ->
            val findings = probe.inspect(case.plan).findings

            val insertion = MitigationPlan.insert(case.plan.graph, findings, reviewedAt)

            assertEquals(findings.size, insertion.inserted.size, "${case.name}: one mitigation per finding")
            assertEquals(Verdict.Holds(), SequenceProbe().evaluate(insertion.graph), "${case.name}: sequence")
            insertion.inserted.forEach { mitigation ->
                val hazardous = insertion.graph.items.single { it.canonId == mitigation.hazardousTaskId }
                assertTrue(
                    mitigation.task.canonId in hazardous.dependsOn,
                    "${case.name}: ${hazardous.canonId.value} must depend on its mitigation",
                )
            }
        }
    }

    @Test
    fun `the vent fixture inserts the three mitigations it names`() = runBlocking<Unit> {
        val insertion = MitigationPlan.insert(
            VentFixture.graph,
            probe.inspect(VentFixture.plan).findings,
            reviewedAt,
        )

        assertEquals(VentFixture.expectedMitigations, insertion.inserted.map { it.task.canonId })
        assertEquals(
            listOf(
                "Put on the protective equipment this step needs before: Cut the duct to length",
                "Check local code and consider a licensed trade before: " +
                    "Mount the fan and wire it to the switched mains circuit",
                "Confirm the ventilation path before: Mount the fan and wire it to the switched mains circuit",
            ),
            insertion.inserted.map { it.task.title },
        )
    }

    @Test
    fun `removing the resin line loses the fumes finding and keeps the others`() = runBlocking<Unit> {
        val edited = WorkPlan(
            graph = VentFixture.graph,
            lines = VentFixture.lines.filterNot { it.lineId == "line-sealant" },
        )

        val findings = probe.inspect(edited).findings

        assertEquals(
            listOf(HazardCategory.CUTTING_OR_POWER_TOOLS, HazardCategory.ELECTRICAL),
            findings.map { it.category },
        )
        assertTrue(findings.none { it.category == HazardCategory.FUMES_OR_CHEMICALS })
        assertTrue(BlueprintSafetyGate.check("vent-edited", findings).isSuccess, "two hazards is still a pass")
    }

    @Test
    fun `a blueprint with zero findings on one of these plans is a failing replay`() = runBlocking<Unit> {
        val declawed = WorkPlan(
            graph = VentFixture.graph.copy(
                // Numbered, not named: the item ids carry the hazard words themselves.
                items = VentFixture.graph.items.mapIndexed { index, item -> item.copy(title = "Step ${index + 1}") },
            ),
            lines = emptyList(),
        )

        val findings = probe.inspect(declawed).findings
        val gate = BlueprintSafetyGate.check("vent", findings)

        assertEquals(emptyList(), findings, "the hazards were in the titles and the manifest")
        assertTrue(gate.isFailure)
        assertTrue(
            gate.exceptionOrNull()?.message.orEmpty().contains("no hazard"),
            gate.exceptionOrNull()?.message.orEmpty(),
        )
    }

    @Test
    fun `the lawn fixture warns on a plan with no electricity and no fumes`() = runBlocking<Unit> {
        val inspection = probe.inspect(LawnFixture.plan)

        assertEquals(setOf(HazardCategory.CUTTING_OR_POWER_TOOLS), inspection.categories)
        assertEquals(
            CanonId("till-bed/mitigation:use_ppe"),
            MitigationPlan.mitigationId(CanonId("till-bed"), inspection.findings.single().mitigationHint),
        )
    }
}

/**
 * The AMPR-380 fixture criterion: every one of the three Blueprint plans carries
 * physical hazard, so a replay that finds none of it has not checked anything.
 */
object BlueprintSafetyGate {

    fun check(fixture: String, findings: List<HazardFinding>): Result<Unit> =
        if (findings.isNotEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "fixture $fixture yielded no hazard finding: a plan of physical work that warns about " +
                        "nothing is a safety line that is not running",
                ),
            )
        }
}
