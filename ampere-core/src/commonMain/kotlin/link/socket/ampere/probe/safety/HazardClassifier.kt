package link.socket.ampere.probe.safety

import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.probe.UndeterminedCause

/**
 * Decides which [HazardCategory] a piece of planned work carries (AMPR-380).
 *
 * v1 ships one implementation, [KeywordHazardClassifier]: deterministic keyword
 * and interface-kind rules, no inference, 0W. A model-backed classifier is a
 * separate, later decision precisely because it would be metered — the probe
 * runs on every plan edit, and a safety line that costs Watts per keystroke is
 * one a host will switch off.
 *
 * **Both methods return `Result`, and that is the load-bearing part of the
 * signature.** A bare `List<HazardFinding>` cannot distinguish "no hazard here"
 * from "I could not read this" — an empty list would make every failure a
 * silent pass, which is exactly what Socket decision D20 forbids. A failure
 * here becomes `Verdict.Undetermined` and is rendered as itself. Prefer
 * [UnclassifiableSubject] as the failure value so the cause survives; any other
 * throwable is read as [UndeterminedCause.EVIDENCE_UNREADABLE].
 */
interface HazardClassifier {

    /**
     * Hazards in one Task, given the manifest lines that Task uses.
     *
     * @param lines only the lines whose [LineRef.appliesTo] names [task]. The
     *   caller does the join; see [SafetyProbe].
     */
    suspend fun classify(task: CanonWorkItem, lines: List<LineRef>): Result<List<HazardFinding>>

    /**
     * Hazards in manifest lines that **no** Task of the graph uses.
     *
     * Abstract rather than defaulted to the empty list: a classifier that wants
     * to ignore unattached lines must say so in its own body, because the
     * default would be a silent drop of a hazard the plan really carries.
     * Findings here name [HazardSubject.Line] — there is no Task to sequence a
     * mitigation against.
     */
    suspend fun classifyUnattachedLines(lines: List<LineRef>): Result<List<HazardFinding>>
}

/**
 * A subject the classifier could not judge, carried as a `Result.failure` value.
 *
 * Named [undeterminedCause] rather than `cause` because `Throwable.cause` is
 * already taken, and shadowing it would hide a real exception chain.
 *
 * [undeterminedCause] is the `UndeterminedCause` the Probe will report, so the remedy is
 * machine-readable at the point the verdict is reached: a Task with no readable
 * text needs a person to write one ([UndeterminedCause.EVIDENCE_ABSENT]), while
 * a classifier that errored needs re-running ([UndeterminedCause.EVIDENCE_UNREADABLE]).
 */
class UnclassifiableSubject(
    val subject: HazardSubject,
    val undeterminedCause: UndeterminedCause,
    override val message: String,
) : Exception(message)

/** A subject the classifier could not judge, as the inspection records it. */
@Serializable
data class UnclassifiedSubject(
    val subject: HazardSubject,
    val reason: String,
    val cause: UndeterminedCause,
)

/** Shorthand for the failure path of [HazardClassifier.classify]. */
fun <T> unclassifiableTask(taskId: CanonId, cause: UndeterminedCause, message: String): Result<T> =
    Result.failure(UnclassifiableSubject(HazardSubject.Task(taskId), cause, message))
