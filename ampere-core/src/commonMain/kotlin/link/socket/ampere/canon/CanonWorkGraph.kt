package link.socket.ampere.canon

import kotlinx.serialization.Serializable

/**
 * A project and its work items as one value, assembled by the caller from one
 * Link's canon. The subject type for structural Probes
 * (`link.socket.ampere.probe.SequenceProbe`), and the canon's first
 * *composite* value type — it is not a [CanonEntity], carries no provenance
 * of its own, and has no [CanonType]; each member keeps its own.
 *
 * Deliberately a plain data class with **no `init` validation**. A graph
 * whose [CanonWorkItem.dependsOn] edges dangle or form a cycle must be
 * constructible, so it can be recorded in a trace and then *convicted* by a
 * Probe. Rejecting it at construction would make the defect unrepresentable —
 * and, once serialized, undecodable — which is exactly the failure the
 * wire-stability invariant in `docs/concepts/domain-canon.md` exists to
 * prevent.
 *
 * Same-Link rule: every [CanonId] in [items] and [milestones] is expected to
 * name an entity from the same Link as [project]. The caller assembling the
 * graph owns that filter; nothing here checks it.
 *
 * @property project The project the items belong to.
 * @property milestones The project's milestones, if the caller perceived any.
 *   Carried for completeness; no structural Probe reads them yet — milestone
 *   ordering edges are out of scope for AMPR-322.
 * @property items The work items whose [CanonWorkItem.dependsOn] edges form
 *   the graph. Order is preserved and is the order a Probe walks them in, so
 *   a verdict's reason is deterministic for a given graph.
 */
@Serializable
data class CanonWorkGraph(
    val project: CanonProject,
    val milestones: List<CanonMilestone> = emptyList(),
    val items: List<CanonWorkItem>,
)
