package link.socket.ampere.probe.safety

import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProse
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus

/**
 * Turns [HazardFinding]s into work: one mitigation Task per finding, sequenced
 * *before* the hazardous Task (AMPR-380).
 *
 * Pure and deterministic — a graph in, a graph out, no clock of its own and no
 * I/O — so the Inspector rule that calls it ([link.socket.ampere.room.SafetyReview])
 * is testable without a Room, and a replay reproduces the same ids.
 *
 * **How it sequences.** A mitigation Task copies the hazardous Task's
 * `dependsOn` and the hazardous Task gains an edge to the mitigation. That slots
 * the mitigation into the chain where the hazardous Task already sat, and it
 * cannot create a cycle: nothing else in the graph points at a Task that did not
 * exist a moment ago. A graph that was *already* cyclic keeps its cycle — this
 * never silently repairs one, because `SequenceProbe` convicting it is how a
 * cycle is meant to be reported (AMPR-322: reported, never dropped).
 *
 * **Idempotence by identity.** [mitigationId] is derived from the hazardous Task
 * and the hint, so re-running over an already-mitigated graph inserts nothing and
 * returns the graph unchanged. The same identity-is-the-lookup rule the Room's
 * thread ids use.
 *
 * **A mitigation never gets a mitigation.** A mitigation Task's title quotes the
 * step it guards ("Put on the protective equipment this step needs before: Cut the
 * duct to length"), and a keyword classifier reading that title finds the same
 * hazard again — so inserting would regress forever. [isMitigation] is the stop,
 * and `SafetyProbe` applies it one step earlier by not inspecting a mitigation
 * Task at all: a mitigation is the safety step, not hazardous work.
 *
 * **What the Task carries.** The category and the hint ride in
 * `CanonWorkItem.labels` under [HAZARD_LABEL_PREFIX] and
 * [MITIGATION_LABEL_PREFIX], and the finding's evidence rides in its description.
 * Labels rather than a new field: a consumer renders the hazard from them
 * (Socket owns the copy), and `CanonWorkItem` needs no change — the canon's
 * closed shape is not reopened for a Probe's convenience.
 */
object MitigationPlan {

    /** Label prefix carrying the [HazardCategory] a mitigation Task answers. A wire contract. */
    const val HAZARD_LABEL_PREFIX: String = "hazard:"

    /** Label prefix carrying the [MitigationHint] a mitigation Task performs. A wire contract. */
    const val MITIGATION_LABEL_PREFIX: String = "mitigation:"

    /**
     * `sourceSystem` for a mitigation Task's provenance.
     *
     * Every canon entity carries provenance, and a mitigation Task has none from a
     * provider — Ampere proposed it. So the handle keeps the hazardous Task's
     * `linkId` (the same-Link rule: every `CanonId` in a graph names an entity of
     * one Link) and says plainly in `sourceSystem` that the origin is this Probe,
     * rather than impersonating the provider the hazardous Task came from. The
     * native payload and etag are dropped: there is no native object to merge
     * against, and creating one is `CreatingCanonAdapter.create`'s job.
     */
    const val DERIVED_SOURCE_SYSTEM: String = "ampere.probe.safety"

    /** One inserted mitigation and the finding that asked for it. */
    data class InsertedMitigation(
        val finding: HazardFinding,
        val hazardousTaskId: CanonId,
        val task: CanonWorkItem,
    )

    /** The graph after insertion, and what went in. [inserted] is empty when nothing changed. */
    data class Insertion(
        val graph: CanonWorkGraph,
        val inserted: List<InsertedMitigation> = emptyList(),
    )

    /**
     * True when [task] is a mitigation Task — it carries a
     * [MITIGATION_LABEL_PREFIX] label.
     *
     * The label is the contract rather than the id shape, because a consumer that
     * round-trips the Task through a provider may not keep the id. A consumer that
     * labels its own Task this way opts out of the hazard check for it, which is
     * its graph's business.
     */
    fun isMitigation(task: CanonWorkItem): Boolean =
        task.labels.any { it.startsWith(MITIGATION_LABEL_PREFIX) }

    /**
     * The id of the mitigation Task that answers [hint] on [task]. Pure, so the
     * lookup is the id and a second insertion is a no-op.
     */
    fun mitigationId(task: CanonId, hint: MitigationHint): CanonId =
        CanonId("${task.value}/mitigation:${hint.name.lowercase()}")

    /**
     * Insert one mitigation Task per finding whose subject is a Task of [graph].
     *
     * Findings on a manifest line ([HazardSubject.Line]) and findings on a Task
     * the graph does not contain insert nothing — there is no step to sequence
     * against — and are simply absent from [Insertion.inserted]. They still reached
     * the Room as findings, which is where a person decides what to do about them.
     *
     * @param at the moment Ampere formed the proposal; becomes the mitigation
     *   Task's `observedAt`. Injected rather than read from a clock so a replay is
     *   byte-identical.
     */
    fun insert(graph: CanonWorkGraph, findings: List<HazardFinding>, at: Instant): Insertion {
        val byId = graph.items.associateBy { it.canonId }
        val present = graph.items.mapTo(mutableSetOf()) { it.canonId }
        val inserted = mutableListOf<InsertedMitigation>()

        findings.forEach { finding ->
            val taskId = (finding.subject as? HazardSubject.Task)?.canonId ?: return@forEach
            val hazardous = byId[taskId] ?: return@forEach
            if (isMitigation(hazardous)) return@forEach
            val mitigationId = mitigationId(taskId, finding.mitigationHint)
            if (!present.add(mitigationId)) return@forEach

            inserted += InsertedMitigation(
                finding = finding,
                hazardousTaskId = taskId,
                task = CanonWorkItem(
                    canonId = mitigationId,
                    provenance = hazardous.provenance.derived(mitigationId.value, at),
                    title = title(finding, hazardous),
                    status = CanonWorkStatus.TODO,
                    projectId = hazardous.projectId,
                    labels = listOf(
                        HAZARD_LABEL_PREFIX + finding.category.name,
                        MITIGATION_LABEL_PREFIX + finding.mitigationHint.name,
                    ),
                    description = CanonProse.bounded(finding.evidence),
                    // Where the hazardous Task sat: the mitigation takes its place in the chain.
                    dependsOn = hazardous.dependsOn,
                ),
            )
        }

        if (inserted.isEmpty()) return Insertion(graph)

        val mitigationsByTask = inserted.groupBy({ it.hazardousTaskId }, { it.task })
        val items = graph.items.flatMap { item ->
            val mitigations = mitigationsByTask[item.canonId].orEmpty()
            // Listed immediately before the Task they guard, so a reader walking the
            // graph in order reads the plan in the order it must happen.
            mitigations + item.copy(dependsOn = item.dependsOn + mitigations.map { it.canonId })
        }

        return Insertion(graph = graph.copy(items = items), inserted = inserted)
    }

    /**
     * The mitigation Task's title: the hint's generic imperative, then the step it
     * guards. Free of any consumer's domain vocabulary — a renderer that wants
     * "consider an electrician for the mains connection" reads the category off the
     * labels and writes its own.
     */
    fun title(finding: HazardFinding, hazardous: CanonWorkItem): String =
        "${finding.mitigationHint.imperative} before: ${hazardous.title}"

    private fun CanonProvenance.derived(nativeId: String, at: Instant): CanonProvenance =
        CanonProvenance(
            sourceHandle = sourceHandle.copy(
                sourceSystem = DERIVED_SOURCE_SYSTEM,
                nativeId = nativeId,
                etag = null,
            ),
            observedAt = at,
            nativePayload = null,
        )
}
