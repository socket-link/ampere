package link.socket.ampere.eval.blueprint

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId
import link.socket.ampere.probe.safety.HazardCategory
import link.socket.ampere.probe.safety.HazardFinding
import link.socket.ampere.probe.safety.HazardSubject
import link.socket.ampere.probe.safety.MitigationHint
import link.socket.ampere.probe.safety.PlanLine
import link.socket.ampere.probe.safety.WorkPlan

/**
 * The lawn fixture (AMPR-380, socket#1406 Phase 2c): a back-lawn renovation from
 * a Blueprint template, where the hazard is a machine somebody hires for a day.
 *
 * The third of the three plans the safety replay reads, and the one with no
 * electricity and no fumes in it at all: a plan whose only hazard is a powered
 * tool must still come back `Warn`, or the probe is only reading the words it
 * expects to find. Hiring the machine is not the hazardous step — running it is —
 * and the fixture is titled so that the finding lands where the work is.
 */
object LawnFixture {

    val observedAt: Instant = Instant.parse("2026-09-21T09:00:00Z")

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("reminders"),
            sourceSystem = "reminders",
            nativeId = "lawn-3",
            etag = null,
        ),
        observedAt = observedAt,
        nativePayload = NativePayload(schema = NativeSchema("Reminder"), fields = JsonObject(emptyMap())),
    )

    val project: CanonProject = CanonProject(
        CanonId("lawn-3"),
        provenance,
        name = "Back lawn renovation",
        status = CanonWorkStatus.IN_PROGRESS,
    )

    private fun item(id: String, title: String, vararg dependsOn: String) = CanonWorkItem(
        CanonId(id),
        provenance,
        title = title,
        status = CanonWorkStatus.TODO,
        projectId = project.canonId,
        dependsOn = dependsOn.map(::CanonId),
    )

    val graph: CanonWorkGraph = CanonWorkGraph(
        project = project,
        items = listOf(
            item("test-soil", "Test the soil pH"),
            item("order-supplies", "Order seed and compost", "test-soil"),
            item("book-hire", "Reserve the machine hire for Saturday", "test-soil"),
            item("till-bed", "Till the new bed and aerate the lawn", "book-hire", "order-supplies"),
            item("spread-seed", "Spread the seed and water it in", "till-bed"),
        ),
    )

    val lines: List<PlanLine> = listOf(
        PlanLine(
            lineId = "line-tiller",
            kind = "POWER_TOOL",
            label = "Rear-tine tiller and core aerator (day hire)",
            appliesTo = setOf(CanonId("till-bed")),
        ),
        PlanLine(
            lineId = "line-seed",
            kind = "CONSUMABLE",
            label = "Ryegrass seed and compost",
            appliesTo = setOf(CanonId("order-supplies"), CanonId("spread-seed")),
        ),
    )

    val plan: WorkPlan = WorkPlan(graph, lines)

    val expectedFindings: List<HazardFinding> = listOf(
        HazardFinding(
            category = HazardCategory.CUTTING_OR_POWER_TOOLS,
            subject = HazardSubject.Task(CanonId("till-bed")),
            evidence = "manifest line line-tiller has kind POWER_TOOL",
            mitigationHint = MitigationHint.USE_PPE,
        ),
    )

    val expectedSafetyReason: String = "1 hazard(s): CUTTING_OR_POWER_TOOLS on till-bed"
}
