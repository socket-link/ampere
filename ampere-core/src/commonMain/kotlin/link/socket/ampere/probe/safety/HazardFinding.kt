package link.socket.ampere.probe.safety

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonId

/**
 * What a [HazardFinding] is about (AMPR-380).
 *
 * Two shapes, because a hazard reaches a plan two ways: a Task that *describes*
 * hazardous work, and a manifest line that *is* a hazardous thing. Keeping them
 * one type with two cases is what lets [MitigationPlan] insert a mitigation Task
 * only where there is a Task to insert it before, and still report the rest.
 *
 * [value] is the string a `ProbeReport` or `ProbeEvent.VerdictReached` carries,
 * since a verdict event is primitives plus `Verdict` and never the subject.
 */
@Serializable
sealed interface HazardSubject {

    /** The identifier a trace, a Room thread, or a verdict event names this subject by. */
    val value: String

    /** A work item of the plan graph. The only subject a mitigation Task can be sequenced against. */
    @Serializable
    @SerialName("HazardSubject.Task")
    data class Task(val canonId: CanonId) : HazardSubject {
        override val value: String get() = canonId.value
    }

    /**
     * A manifest line, named when no Task in the graph uses it.
     *
     * A solvent in the manifest that no step mentions is still in the room with
     * the person. There is nothing to sequence a mitigation before, so the
     * finding is the whole remedy: it reaches the Room, and whoever owns the
     * plan decides which step it belongs to.
     */
    @Serializable
    @SerialName("HazardSubject.Line")
    data class Line(val lineId: String) : HazardSubject {
        override val value: String get() = lineId
    }
}

/**
 * One hazard, on one subject, with the evidence that found it and what to do
 * about it (AMPR-380).
 *
 * Primitives and Ampere-owned enums only, so a finding can ride a Room card, a
 * Task label, or a trace payload without dragging a subject type across the
 * boundary.
 *
 * @property category The hazard. Closed vocabulary; see [HazardCategory].
 * @property subject The Task or manifest line the hazard was found on.
 * @property evidence Why the classifier said so, in one phrase — the matched
 *   word or the line kind. Deterministic for a given subject, so two runs over
 *   the same plan produce byte-identical findings and a replay can compare them.
 * @property mitigationHint The one thing to do before the hazardous work. One
 *   hint is one mitigation Task.
 */
@Serializable
data class HazardFinding(
    val category: HazardCategory,
    val subject: HazardSubject,
    val evidence: String,
    val mitigationHint: MitigationHint,
)
