package link.socket.ampere.propel

import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.field.FieldDelta
import link.socket.ampere.llm.decide.Judgment
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.probe.Probe
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict

/**
 * The drift receptor: has the world moved enough since the host seat last perceived it that
 * the next cycle should perceive again? (AMPR-483, epic AMPR-478 rows N3 and N5; the shape
 * AMPR-381 J6 named — a decision-backed Probe.)
 *
 * A [Probe] over the [RunDrift] the run builds after each executed cycle, whose [evaluate]
 * maps one measured judgment onto the four-valued [Verdict] every Probe answers with:
 *
 * - a delta with nothing in it is [Verdict.Holds], with no call — nothing moved;
 * - a delta whose only entries are `PROTECTED` rows is [Verdict.Violated], with no call —
 *   something moved, and what moved cannot be shown to a judge off the device, so the
 *   receptor convicts rather than acquits on evidence it may not read;
 * - otherwise [RunQuestions.perceptionDrift] is asked through [reasoning] over
 *   [RunDrift.asDecisionState], and the probability the judge put on `true` is read against
 *   [moveAbove]: at or above it [Verdict.Violated], below it [Verdict.Holds];
 * - a judgment with no measured distribution — a self-reported answer, or one the transport
 *   did not return — and a transport that throws are both [Verdict.Undetermined] with
 *   [UndeterminedCause.EVIDENCE_UNREADABLE]: the evidence exists and could not be used. The
 *   threshold folds what would otherwise be a middle band, so no new cause is needed.
 *
 * The judgment's record is the decision call's own — `AgentReasoning.decide` leaves one
 * `JudgmentRecorded` per question under `RunQuestions.PERCEPTION_DRIFT_ID`, filed under
 * OBSERVE — and the verdict's is the `ProbeSuite` the run evaluates this receptor inside,
 * over the host seat's door under the run id, which leaves one `ProbeEvent.VerdictReached`
 * beside it. Evaluated on its own, as a test does, the receptor records nothing but what
 * [reasoning] records.
 *
 * [alsoAsk] rides in the same request: under `LearnCadence.Record` the run puts
 * [RunQuestions.lessonWorthKeeping] here, so the cycle's two receptor questions stay one
 * request over one state and one `stateDigest`, as AMPR-481 made them. The receptor reads
 * only the drift judgment; every other answer is handed back on [judgments] and read by
 * nothing in this ticket.
 *
 * [moveAbove] is read from the constructor and appears as no constant anywhere in Ampere
 * (AMPR-422, Q5): the number is the consumer's, from `PerceiveCadence.WhenDrifted`.
 *
 * With no [moveAbove] the receptor has no judge (`PerceiveCadence.WhenMoved`, AMPR-487 — the
 * rule AMPR-482's STOP was answered with): the first two rules above are unchanged, and every
 * other non-empty delta is [Verdict.Violated] as "moved: n changes", with no call. [reasoning]
 * is never asked, so a host seat with no decision transport can run it, and [alsoAsk] must be
 * empty — with no request of the receptor's own there is nothing for a question to ride in.
 *
 * @property reasoning The host seat's reasoning unit, whose `decide` carries the seat's
 *   decision transport and records the judgment through the seat's door.
 * @property moveAbove The probability of `true` at or above which the verdict is `Violated`,
 *   or null for no judge: anything that moved is `Violated`.
 * @property clip How many characters of any one text reach the state the judge is shown
 *   (`RunPolicy.judgmentResultClip`).
 * @property alsoAsk Questions to carry in the same request beside the drift question, keyed
 *   by their record ids. May not reuse [RunQuestions.PERCEPTION_DRIFT_ID].
 */
internal class DriftReceptor(
    private val reasoning: AgentReasoning,
    private val moveAbove: Double?,
    private val clip: Int,
    private val alsoAsk: Map<String, Question> = emptyMap(),
) : Probe<RunDrift> {

    init {
        require(moveAbove == null || (moveAbove > 0.0 && moveAbove < 1.0)) {
            "moveAbove is a probability strictly between 0 and 1; got $moveAbove"
        }
        require(moveAbove != null || alsoAsk.isEmpty()) {
            "a receptor with no judge makes no request, so nothing can ride in one; got ${alsoAsk.keys}"
        }
        require(clip > 0) { "a clipped result has at least one character; got $clip" }
        require(RunQuestions.PERCEPTION_DRIFT_ID !in alsoAsk) {
            "'${RunQuestions.PERCEPTION_DRIFT_ID}' is the receptor's own question and cannot be asked beside it"
        }
    }

    override val id: ProbeId = ProbeId(PROBE_ID)

    /**
     * Every judgment the last [evaluate]'s request came back with, keyed by question id —
     * the drift judgment and whatever [alsoAsk] carried — or empty when the last evaluation
     * made no call or the call failed. Replaced on every evaluation.
     */
    var judgments: Map<String, Judgment> = emptyMap()
        private set

    override suspend fun evaluate(subject: RunDrift): Verdict {
        judgments = emptyMap()
        val delta = subject.delta
        if (delta.isEmpty) return Verdict.Holds("nothing moved since cycle ${subject.cycle}")
        if (delta.movedBehindSensitivity) {
            return Verdict.Violated(
                "moved behind sensitivity: ${delta.protectedLeftOut} protected " +
                    "${if (delta.protectedLeftOut == 1) "entry" else "entries"} since cycle ${subject.cycle}, " +
                    "which no judge off the device may read",
            )
        }
        val threshold = moveAbove
            ?: return Verdict.Violated(
                "moved: ${delta.entryCount} ${if (delta.entryCount == 1) "change" else "changes"} " +
                    "since cycle ${subject.cycle}",
            )

        val response = try {
            reasoning.decide(
                state = subject.asDecisionState(clip),
                questions = mapOf(RunQuestions.PERCEPTION_DRIFT_ID to RunQuestions.perceptionDrift) + alsoAsk,
                phase = CognitivePhase.OBSERVE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            return Verdict.Undetermined(
                reason = "the drift question could not be answered: " +
                    (throwable.message ?: throwable::class.simpleName),
                cause = UndeterminedCause.EVIDENCE_UNREADABLE,
            )
        }
        judgments = response.judgments

        val judgment = response.judgments[RunQuestions.PERCEPTION_DRIFT_ID]
            ?: return Verdict.Undetermined(
                reason = "the transport returned no answer to '${RunQuestions.PERCEPTION_DRIFT_ID}'",
                cause = UndeterminedCause.EVIDENCE_UNREADABLE,
            )
        val distribution = judgment.distribution
        if (judgment.source != ConfidenceSource.MEASURED || distribution == null) {
            return Verdict.Undetermined(
                reason = "self-reported answer: no measured distribution to read against $threshold",
                cause = UndeterminedCause.EVIDENCE_UNREADABLE,
            )
        }
        val bearsOnGoal = distribution[Question.Noul.TRUE_KEY]
            ?: return Verdict.Undetermined(
                reason = "the measured distribution carries no mass for '${Question.Noul.TRUE_KEY}'",
                cause = UndeterminedCause.EVIDENCE_UNREADABLE,
            )

        val reading = "p(bears on goal) = ${bearsOnGoal.rounded()}"
        return if (bearsOnGoal >= threshold) {
            Verdict.Violated("$reading, at or above ${threshold.rounded()}")
        } else {
            Verdict.Holds("$reading, below ${threshold.rounded()}")
        }
    }

    companion object {
        /** The receptor's id, the one every `VerdictReached` it leaves carries. */
        const val PROBE_ID: String = "perception-drift"
    }
}

/**
 * Whether every entry of this delta is a `PROTECTED` belief or observation: something moved,
 * and none of it may be rendered for a judge off the device. False for the empty delta.
 */
private val FieldDelta.movedBehindSensitivity: Boolean
    get() = !isEmpty &&
        goalsChanged.isEmpty() &&
        commitmentsOpened.isEmpty() &&
        commitmentsResolved.isEmpty() &&
        actionsByOthers.isEmpty() &&
        subjectsAdded.isEmpty() &&
        relationshipsAdded.isEmpty() &&
        protectedLeftOut == beliefsAdded.size + observationsAdded.size

/** How many entries moved, `PROTECTED` ones included: what a judgeless verdict reports. */
private val FieldDelta.entryCount: Int
    get() = goalsChanged.size +
        commitmentsOpened.size +
        commitmentsResolved.size +
        beliefsAdded.size +
        observationsAdded.size +
        actionsByOthers.size +
        subjectsAdded.size +
        relationshipsAdded.size

/** A probability to two decimal places, for a verdict's reason. */
private fun Double.rounded(): String = ((this * 100).roundToInt() / 100.0).toString()
