package link.socket.ampere.probe.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.ProbeRegistry
import link.socket.ampere.probe.ProbeSuite
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict
import link.socket.ampere.probe.registerAmpereProbes
import link.socket.ampere.probe.safety.SafetyFixtures.graph
import link.socket.ampere.probe.safety.SafetyFixtures.line
import link.socket.ampere.probe.safety.SafetyFixtures.task

/** AMPR-380 task 1 validation: the four-valued verdict a hazard check is allowed to reach. */
class SafetyProbeTest {

    private val probe = SafetyProbe(KeywordHazardClassifier)

    @Test
    fun `a plan with a hazard warns and never violates`() = runTest {
        val plan = WorkPlan(graph(task("cut-duct", title = "Cut the duct to length")))

        val verdict = probe.evaluate(plan)

        assertIs<Verdict.Warn>(verdict)
        assertEquals("1 hazard(s): CUTTING_OR_POWER_TOOLS on cut-duct", verdict.reason)
    }

    @Test
    fun `a plan with no hazard holds and says how much it read`() = runTest {
        val plan = WorkPlan(graph(task("order", title = "Order the parts"), task("wait", title = "Wait for delivery")))

        assertEquals(Verdict.Holds(reason = "no hazard category in 2 task(s)"), probe.evaluate(plan))
    }

    @Test
    fun `an empty plan holds`() = runTest {
        assertEquals(Verdict.Holds(reason = "no hazard category in 0 task(s)"), probe.evaluate(WorkPlan(graph())))
    }

    @Test
    fun `a task the classifier cannot read is undetermined and never a soft pass`() = runTest {
        val plan = WorkPlan(graph(task("blank", title = " ")))

        val verdict = probe.evaluate(plan)

        assertIs<Verdict.Undetermined>(verdict)
        assertEquals(UndeterminedCause.EVIDENCE_ABSENT, verdict.cause)
        assertTrue(verdict.reason.contains("blank"), verdict.reason)
    }

    @Test
    fun `undetermined outranks warn and keeps the findings it did reach`() = runTest {
        val plan = WorkPlan(graph(task("cut-duct", title = "Cut the duct"), task("blank", title = "")))

        val inspection = probe.inspect(plan)
        val verdict = inspection.verdict

        assertIs<Verdict.Undetermined>(verdict)
        assertTrue(verdict.reason.contains("1 hazard(s) found in the rest"), verdict.reason)
        assertEquals(listOf(HazardCategory.CUTTING_OR_POWER_TOOLS), inspection.findings.map { it.category })
    }

    @Test
    fun `a classifier failure that is not typed is read as unreadable`() = runTest {
        val broken = object : HazardClassifier {
            override suspend fun classify(task: CanonWorkItem, lines: List<LineRef>) =
                Result.failure<List<HazardFinding>>(IllegalStateException("provider refused"))

            override suspend fun classifyUnattachedLines(lines: List<LineRef>) =
                Result.success(emptyList<HazardFinding>())
        }

        val verdict = SafetyProbe(broken).evaluate(WorkPlan(graph(task("t"))))

        assertIs<Verdict.Undetermined>(verdict)
        assertEquals(UndeterminedCause.EVIDENCE_UNREADABLE, verdict.cause)
        assertTrue(verdict.reason.contains("provider refused"), verdict.reason)
    }

    @Test
    fun `findings come back in plan order`() = runTest {
        val plan = WorkPlan(
            graph(
                task("cut-duct", title = "Cut the duct"),
                task("mount-fan", title = "Wire the fan to mains"),
                task("tidy", title = "Sweep up"),
            ),
        )

        val inspection = probe.inspect(plan)

        assertEquals(listOf("cut-duct", "mount-fan"), inspection.findings.map { it.subject.value })
        assertEquals(
            setOf(HazardCategory.CUTTING_OR_POWER_TOOLS, HazardCategory.ELECTRICAL),
            inspection.categories,
        )
    }

    @Test
    fun `a hazardous line no task uses is still reported`() = runTest {
        val plan = WorkPlan(
            graph = graph(task("order", title = "Order the parts")),
            lines = listOf(
                line("line-solvent", kind = "SOLVENT", label = "Acetone"),
                line("line-fan", kind = "MAINS_VOLTAGE", label = "Fan", appliesTo = setOf("order")),
            ),
        )

        val inspection = probe.inspect(plan)

        assertEquals(
            listOf(HazardSubject.Task(CanonId("order")), HazardSubject.Line("line-solvent")),
            inspection.findings.map { it.subject },
            "an attached line is judged with its task and an unattached one against itself",
        )
    }

    @Test
    fun `the warn reason is bounded however big the plan is`() = runTest {
        val plan = WorkPlan(graph(*(1..9).map { task("cut-$it", title = "Cut part $it") }.toTypedArray()))

        val verdict = probe.evaluate(plan)

        assertIs<Verdict.Warn>(verdict)
        assertTrue(verdict.reason.endsWith("and 5 more"), verdict.reason)
        assertEquals(4, verdict.reason.split("CUTTING_OR_POWER_TOOLS").size - 1, verdict.reason)
    }

    @Test
    fun `the probe runs in a suite and is listed by the ampere wiring`() = runTest {
        val plan = WorkPlan(graph(task("mount-fan", title = "Wire the fan to mains")))
        val reports = ProbeSuite(listOf(probe)).evaluate(subjectId = "plan-1", subject = plan)

        assertEquals(listOf(ProbeId(SafetyProbe.ID)), reports.map { it.probeId })
        assertIs<Verdict.Warn>(reports.single().verdict)

        val registered = ProbeRegistry().registerAmpereProbes().get(ProbeId("ampere.safety"))
        assertIs<SafetyProbe>(registered)
    }
}
