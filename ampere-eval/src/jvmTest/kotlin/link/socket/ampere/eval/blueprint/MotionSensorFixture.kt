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
 * The motion-sensor fixture (AMPR-380, socket#1406 Phase 2c): a hallway PIR
 * sensor on a microcontroller, as a Blueprint plan.
 *
 * The second of the three plans the safety replay reads. It is here to prove the
 * check is not vent-specific and that the electrical category is reached by the
 * *kind* of a manifest line as well as by a word in a title — the supply line
 * says `LOW_VOLTAGE`, which is bench wiring, so the mitigation is to make the
 * circuit dead rather than to call an electrician.
 */
object MotionSensorFixture {

    val observedAt: Instant = Instant.parse("2026-09-21T09:00:00Z")

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("reminders"),
            sourceSystem = "reminders",
            nativeId = "motion-7",
            etag = null,
        ),
        observedAt = observedAt,
        nativePayload = NativePayload(schema = NativeSchema("Reminder"), fields = JsonObject(emptyMap())),
    )

    val project: CanonProject = CanonProject(
        CanonId("motion-7"),
        provenance,
        name = "Hallway motion sensor",
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
            item("pick-board", "Choose a microcontroller board"),
            item("order-parts", "Order the parts", "pick-board"),
            item("wire-sensor", "Wire the PIR sensor to the board", "order-parts"),
            item("flash-firmware", "Flash the firmware", "wire-sensor"),
            item("mount-sensor", "Fix the enclosure to the hallway wall", "flash-firmware"),
            item("verify-trigger", "Verify the trigger range", "mount-sensor"),
        ),
    )

    val lines: List<PlanLine> = listOf(
        PlanLine(
            lineId = "line-board",
            kind = "PART",
            label = "ESP32 development board",
            appliesTo = setOf(CanonId("wire-sensor"), CanonId("flash-firmware")),
        ),
        PlanLine(
            lineId = "line-supply",
            kind = "LOW_VOLTAGE",
            label = "5V USB supply",
            appliesTo = setOf(CanonId("wire-sensor")),
        ),
    )

    val plan: WorkPlan = WorkPlan(graph, lines)

    val expectedFindings: List<HazardFinding> = listOf(
        HazardFinding(
            category = HazardCategory.ELECTRICAL,
            subject = HazardSubject.Task(CanonId("wire-sensor")),
            evidence = "manifest line line-supply has kind LOW_VOLTAGE",
            mitigationHint = MitigationHint.DISCONNECT_POWER_FIRST,
        ),
    )

    val expectedSafetyReason: String = "1 hazard(s): ELECTRICAL on wire-sensor"
}
