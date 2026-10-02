package link.socket.ampere.probe.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.canon.CanonId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.Verdict
import link.socket.ampere.probe.safety.SafetyFixtures.graph
import link.socket.ampere.probe.safety.SafetyFixtures.line
import link.socket.ampere.probe.safety.SafetyFixtures.reviewedAt
import link.socket.ampere.probe.safety.SafetyFixtures.task

/** AMPR-380 task 2 validation: one mitigation Task per hint sequenced before the hazardous Task. */
class MitigationPlanTest {

    private val probe = SafetyProbe(KeywordHazardClassifier)
    private val sequence = SequenceProbe()

    private val plan = WorkPlan(
        graph = graph(
            task("order-parts", title = "Order the parts"),
            task("cut-duct", title = "Cut the duct to length", dependsOn = listOf("order-parts")),
            task("mount-fan", title = "Mount the fan and wire it to mains", dependsOn = listOf("cut-duct")),
        ),
        lines = listOf(
            line("line-fan", kind = "MAINS_VOLTAGE", label = "Inline fan", appliesTo = setOf("mount-fan")),
            line("line-sealant", kind = "RESIN", label = "Duct sealant", appliesTo = setOf("mount-fan")),
        ),
    )

    @Test
    fun `each finding becomes one task that the hazardous task depends on`() = runTest {
        val findings = probe.inspect(plan).findings

        val insertion = MitigationPlan.insert(plan.graph, findings, reviewedAt)

        assertEquals(3, insertion.inserted.size)
        assertEquals(
            listOf(
                "order-parts",
                "cut-duct/mitigation:use_ppe",
                "cut-duct",
                "mount-fan/mitigation:check_local_code",
                "mount-fan/mitigation:confirm_ventilation",
                "mount-fan",
            ),
            insertion.graph.items.map { it.canonId.value },
            "a mitigation is listed immediately before the task it guards",
        )
        val mountFan = insertion.graph.items.single { it.canonId.value == "mount-fan" }
        assertEquals(
            listOf("cut-duct", "mount-fan/mitigation:check_local_code", "mount-fan/mitigation:confirm_ventilation"),
            mountFan.dependsOn.map { it.value },
        )
        val ventilation = insertion.graph.items.single { it.canonId.value.endsWith("confirm_ventilation") }
        assertEquals(
            listOf("cut-duct"),
            ventilation.dependsOn.map { it.value },
            "the mitigation takes the place in the chain the hazardous task had",
        )
    }

    @Test
    fun `the mitigated graph has no cycles and no dangling edges`() = runTest {
        val insertion = MitigationPlan.insert(plan.graph, probe.inspect(plan).findings, reviewedAt)

        assertEquals(Verdict.Holds(), sequence.evaluate(insertion.graph))
    }

    @Test
    fun `a graph that was already cyclic keeps its cycle and gains none`() = runTest {
        val cyclic = graph(
            task("a", title = "Cut the first part", dependsOn = listOf("b")),
            task("b", title = "Sand the second part", dependsOn = listOf("a")),
        )
        val before = sequence.evaluate(cyclic)

        val insertion = MitigationPlan.insert(cyclic, probe.inspect(WorkPlan(cyclic)).findings, reviewedAt)
        val after = sequence.evaluate(insertion.graph)

        assertIs<Verdict.Violated>(before)
        assertIs<Verdict.Violated>(after)
        // Still a cycle, and still reported. The path may be printed from a different
        // entry point, because a new item changes where the walk starts.
        assertTrue(before.reason.startsWith("cycle:"), before.reason)
        assertTrue(after.reason.startsWith("cycle:"), after.reason)
    }

    @Test
    fun `the mitigation task carries the category and the hint as labels`() = runTest {
        val insertion = MitigationPlan.insert(plan.graph, probe.inspect(plan).findings, reviewedAt)

        val electrical = insertion.inserted.single { it.finding.category == HazardCategory.ELECTRICAL }
        assertEquals(
            listOf("hazard:ELECTRICAL", "mitigation:CHECK_LOCAL_CODE"),
            electrical.task.labels,
        )
        assertEquals(
            "Check local code and consider a licensed trade before: Mount the fan and wire it to mains",
            electrical.task.title,
        )
        assertEquals("manifest line line-fan has kind MAINS_VOLTAGE", electrical.task.description?.text)
        assertEquals(CanonId("mount-fan"), electrical.hazardousTaskId)
    }

    @Test
    fun `a mitigation task says in its provenance that ampere derived it`() = runTest {
        val insertion = MitigationPlan.insert(plan.graph, probe.inspect(plan).findings, reviewedAt)

        val mitigation = insertion.inserted.first().task
        val handle = mitigation.provenance.sourceHandle
        assertEquals(MitigationPlan.DERIVED_SOURCE_SYSTEM, handle.sourceSystem)
        assertEquals("cut-duct/mitigation:use_ppe", handle.nativeId)
        assertEquals(
            SafetyFixtures.provenance.sourceHandle.linkId,
            handle.linkId,
            "the same-Link rule: every CanonId in a graph names an entity of one Link",
        )
        assertNull(handle.etag)
        assertNull(mitigation.provenance.nativePayload)
        assertEquals(reviewedAt, mitigation.provenance.observedAt)
    }

    @Test
    fun `inserting twice changes nothing`() = runTest {
        val findings = probe.inspect(plan).findings
        val once = MitigationPlan.insert(plan.graph, findings, reviewedAt)

        val second = probe.inspect(WorkPlan(once.graph, plan.lines)).findings
        val twice = MitigationPlan.insert(once.graph, second, reviewedAt)

        assertEquals(emptyList(), twice.inserted)
        assertEquals(once.graph, twice.graph)
    }

    @Test
    fun `a mitigation task is not itself inspected for hazards`() = runTest {
        val once = MitigationPlan.insert(plan.graph, probe.inspect(plan).findings, reviewedAt)

        val inspection = probe.inspect(WorkPlan(once.graph, plan.lines))

        assertTrue(
            once.inserted.any { MitigationPlan.isMitigation(it.task) },
            "a mitigation task is labelled as one",
        )
        assertEquals(
            listOf("cut-duct", "mount-fan", "mount-fan"),
            inspection.findings.map { it.subject.value },
            "the mitigation tasks quote the hazardous titles and must not be read again",
        )
        assertEquals(3, inspection.tasksInspected, "only the three original tasks were inspected")
    }

    @Test
    fun `a mitigated plan still warns`() = runTest {
        val once = MitigationPlan.insert(plan.graph, probe.inspect(plan).findings, reviewedAt)

        val verdict = probe.evaluate(WorkPlan(once.graph, plan.lines))

        assertIs<Verdict.Warn>(verdict)
    }

    @Test
    fun `a finding on a manifest line inserts nothing`() = runTest {
        val orphan = WorkPlan(
            graph = graph(task("order-parts", title = "Order the parts")),
            lines = listOf(line("line-solvent", kind = "SOLVENT", label = "Acetone")),
        )
        val findings = probe.inspect(orphan).findings

        val insertion = MitigationPlan.insert(orphan.graph, findings, reviewedAt)

        assertTrue(findings.single().subject is HazardSubject.Line)
        assertEquals(emptyList(), insertion.inserted)
        assertEquals(orphan.graph, insertion.graph)
    }

    @Test
    fun `a finding on a task outside the graph inserts nothing`() = runTest {
        val finding = HazardFinding(
            category = HazardCategory.ELECTRICAL,
            subject = HazardSubject.Task(CanonId("not-in-this-plan")),
            evidence = "task text matched \"wire\"",
            mitigationHint = MitigationHint.DISCONNECT_POWER_FIRST,
        )

        val insertion = MitigationPlan.insert(plan.graph, listOf(finding), reviewedAt)

        assertEquals(emptyList(), insertion.inserted)
        assertEquals(plan.graph, insertion.graph)
    }
}
