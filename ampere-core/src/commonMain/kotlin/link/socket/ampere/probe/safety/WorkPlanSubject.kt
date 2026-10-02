package link.socket.ampere.probe.safety

import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkGraph

/**
 * A manifest line as a hazard check needs to see it (AMPR-380).
 *
 * The same move [link.socket.ampere.probe.Observed] makes for freshness: the
 * consumer's own manifest type implements this, so `SafetyProbe` reads what a
 * build is made of without Ampere importing a Socket `ManifestLine`. The Probe's
 * subject is the *binding*, not the manifest.
 *
 * @property lineId The consumer's identifier for the line. Opaque here.
 * @property kind The line's interface kind, e.g. `MAINS_VOLTAGE`, `SOLVENT`.
 *   Deliberately a `String`: interface kinds are an **open** vocabulary owned by
 *   the consumer, unlike the closed [HazardCategory] they map onto.
 *   [KeywordHazardClassifier.recognizedLineKinds] is the token set Ampere's own
 *   classifier normalizes and recognizes; an unrecognized kind is not an error,
 *   it simply contributes no hazard from its kind — its [label] is still read.
 * @property label What the line is, in words. Read as evidence text, so a line
 *   whose kind Ampere does not know can still be caught by what it is called.
 * @property appliesTo The work items that use this line. **The join.** A line
 *   that applies to no item of the graph is reported against itself (see
 *   [HazardSubject.Line]) rather than dropped.
 */
interface LineRef {
    val lineId: String
    val kind: String
    val label: String
    val appliesTo: Set<CanonId>
}

/**
 * What `SafetyProbe` reads: a plan graph and the manifest lines its Tasks use
 * (AMPR-380).
 *
 * An interface rather than a data class for the same reason [LineRef] is one — a
 * consumer that already holds a plan and its manifest implements this on its own
 * type instead of copying both into an Ampere container on every plan edit. Use
 * [WorkPlan] when there is nothing to bind onto.
 *
 * Task 0 of AMPR-380 asked which existing subject exposes Tasks and manifest
 * line kinds together. The answer is that none did: `SequenceProbe`'s subject is
 * `CanonWorkGraph`, which has the Tasks and not the lines, and there is no
 * completeness Probe in this repo. `CanonWorkGraph` is also a final data class,
 * so no subject type can extend it — this interface composes it instead.
 */
interface WorkPlanSubject {
    val graph: CanonWorkGraph
    val lines: List<LineRef>
}

/** The plain [WorkPlanSubject] for a caller with no manifest type of its own to bind. */
data class WorkPlan(
    override val graph: CanonWorkGraph,
    override val lines: List<LineRef> = emptyList(),
) : WorkPlanSubject

/** The plain [LineRef] for a caller with no manifest type of its own to bind. */
data class PlanLine(
    override val lineId: String,
    override val kind: String,
    override val label: String,
    override val appliesTo: Set<CanonId> = emptySet(),
) : LineRef
