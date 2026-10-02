package link.socket.ampere.probe.safety

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProse
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/** Shared values for the AMPR-380 safety tests: one Link, one project, plain Tasks. */
internal object SafetyFixtures {

    val observedAt: Instant = Instant.parse("2026-09-21T09:00:00Z")
    val reviewedAt: Instant = Instant.parse("2026-09-28T09:00:00Z")

    val provenance: CanonProvenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("reminders"),
            sourceSystem = "reminders",
            nativeId = "vent-42",
            etag = null,
        ),
        observedAt = observedAt,
        nativePayload = NativePayload(schema = NativeSchema("Reminder"), fields = JsonObject(emptyMap())),
    )

    val project: CanonProject = CanonProject(
        canonId = CanonId("plan-1"),
        provenance = provenance,
        name = "A plan of physical work",
        status = CanonWorkStatus.IN_PROGRESS,
    )

    fun task(
        id: String,
        title: String = id,
        notes: String? = null,
        labels: List<String> = emptyList(),
        dependsOn: List<String> = emptyList(),
    ): CanonWorkItem = CanonWorkItem(
        canonId = CanonId(id),
        provenance = provenance.copy(sourceHandle = provenance.sourceHandle.copy(nativeId = id)),
        title = title,
        status = CanonWorkStatus.TODO,
        projectId = project.canonId,
        labels = labels,
        description = notes?.let { CanonProse.bounded(it) },
        dependsOn = dependsOn.map(::CanonId),
    )

    fun graph(vararg items: CanonWorkItem): CanonWorkGraph =
        CanonWorkGraph(project = project, items = items.toList())

    fun line(
        id: String,
        kind: String,
        label: String = id,
        appliesTo: Set<String> = emptySet(),
    ): PlanLine = PlanLine(
        lineId = id,
        kind = kind,
        label = label,
        appliesTo = appliesTo.mapTo(linkedSetOf(), ::CanonId),
    )
}
