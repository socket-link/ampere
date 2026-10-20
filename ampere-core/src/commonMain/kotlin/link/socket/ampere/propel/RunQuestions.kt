package link.socket.ampere.propel

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.llm.decide.Question

/**
 * What a hosted run asks about itself (AMPR-421, AMPR-481).
 *
 * Three questions, and the ids their records are filed under. The run asks [goalMet] on
 * the host seat after each executed cycle when [RunPolicy.goalCheck] is
 * [GoalCheck.Record], and the receptor's two — [perceptionDrift], [lessonWorthKeeping] —
 * in a request of their own, over [RunDrift], under a `Record` value of
 * [RunPolicy.cadence]. Every answer is recorded and never read: nothing in the loop, in
 * [RunResult.status][RunResult] or in LEARN consults it. That is deliberate — whether
 * a model can answer these well enough for the run to *act* on them is what AMPR-422 and
 * the receptor bench measure, and the records left here are what they measure against.
 *
 * A consumer asking its own questions declares them on the verifier seat
 * ([HostedAgent.questions]) rather than adding to this object: these are the run's,
 * phrased about the states the run renders, and their ids are reserved.
 */
@AmpereStableApi
object RunQuestions {

    /**
     * The question id [goalMet] is asked under, and therefore the
     * [JudgmentRecorded][link.socket.ampere.agents.domain.event.CognitiveEvent.JudgmentRecorded]
     * `questionId` a reader filters the run's own goal records by.
     *
     * A verifier seat is free to declare a question under any id it likes; using this
     * one makes its records indistinguishable from the run's, which is the one thing
     * worth not doing.
     */
    const val GOAL_MET_ID: String = "goal_met"

    /**
     * "Is the goal met?", as a [Question.Noul].
     *
     * Asked about the state [asDecisionState] renders — the goal, the cycle, and what
     * each step reported — so the two answer descriptions have to be about *evidence*
     * rather than about intent. The `false` branch names three distinct ways a run
     * fails the question, because a model given only "not done" reads a step that
     * succeeded at the wrong thing as done.
     */
    val goalMet: Question.Noul = Question.Noul.of(
        instructions = "Has the goal in the state been achieved by the work its steps report?",
        whenTrue = "Every part of the goal is done, and the step results show it",
        whenFalse = "Some part of the goal is not done, was done to the wrong thing, " +
            "or the results do not show it was done",
    )

    /**
     * The question id [perceptionDrift] is asked under (AMPR-481, row N3), and the
     * `questionId` a reader filters the run's drift records by. Reserved as [GOAL_MET_ID] is.
     */
    const val PERCEPTION_DRIFT_ID: String = "perception_drift"

    /**
     * The question id [lessonWorthKeeping] is asked under (AMPR-481, row N3), and the
     * `questionId` a reader filters the run's lesson records by. Reserved as [GOAL_MET_ID] is.
     */
    const val LESSON_WORTH_KEEPING_ID: String = "lesson_worth_keeping"

    /**
     * "Would a fresh perception read the situation differently?", as a [Question.Noul]
     * (AMPR-481, row N3).
     *
     * Asked about the state [RunDrift.asDecisionState] renders — the goal, the cycle, what
     * the host last perceived, and under `changes` what moved in the Field since
     * (AMPR-479) — on the host seat after each executed cycle when
     * [RunPolicy.cadence]`.perceive` is [PerceiveCadence.Record]. The two answer
     * descriptions are about the *changes* rather than about the world, so a model is told
     * which kinds of change count and which — this run's own recorded work, bookkeeping,
     * detail the earlier perception already covered — do not.
     *
     * Nothing reads the answer. PERCEIVE does not re-run on a `true`; acting on it is the
     * W1 ticket's, once the bench has measured whether a model answers well enough.
     */
    val perceptionDrift: Question.Noul = Question.Noul.of(
        instructions = "Since the agent last perceived the world, the changes under `changes` happened. " +
            "Given what it perceived and its goal, would a fresh perception reach a different " +
            "reading of the situation?",
        whenTrue = "A change bears on the goal: something the plan depends on moved, a goal or " +
            "commitment changed state, a lesson, belief or observation about the goal's subject " +
            "appeared, or another subject acted on it.",
        whenFalse = "Every change is beside the goal: about other subjects, this run's own recorded " +
            "work, bookkeeping, or detail the earlier perception already accounted for.",
    )

    /**
     * "Do this cycle's results hold something a later run should be told?", as a
     * [Question.Noul] (AMPR-481, row N3).
     *
     * Asked about the same state as [perceptionDrift], in the same request, whose
     * `cycle_results` are the steps the cycle just executed — on the host seat after each
     * executed cycle when [RunPolicy.cadence]`.learn` is [LearnCadence.Record]. The `true`
     * branch names the four things worth keeping so a model does not read every success as
     * a lesson.
     *
     * Nothing reads the answer. LEARN still runs once, at `close()`; acting on it is the W1
     * ticket's.
     */
    val lessonWorthKeeping: Question.Noul = Question.Noul.of(
        instructions = "Do this cycle's results under `cycle_results` hold something a later run of a " +
            "similar goal should be told?",
        whenTrue = "A result reveals a reusable fact, a way that worked, a way that failed, or a " +
            "constraint that was not known before.",
        whenFalse = "The results are routine: expected outcomes, nothing that would change how a " +
            "similar goal is approached.",
    )
}

/**
 * This observation as the state of a decision call: compact JSON, in a fixed key order
 * (AMPR-421).
 *
 * ```json
 * {"goal":"Add a retry","cycle":1,"results":[
 *   {"step":"Search","status":"succeeded","origin":"external","result":"found it"}]}
 * ```
 *
 * The key order is fixed, and the only things rendered are the goal's description, the
 * cycle, and per step its description, a status word, where its text came from
 * (`own` or `external`, AMPR-453) and what it reported. A step's id
 * and its timestamps are left out on purpose: the record's
 * [stateDigest][link.socket.ampere.llm.decide.stateDigest] is the only join key back to
 * the state — the state itself is never recorded — so two reads of the same run at the
 * same cycle have to produce the same string, and a timestamp or a minted id would make
 * every digest unique and the join useless.
 *
 * Each reported text is clipped by [clipTo]'s convention, the same one the recalled
 * prompt section uses: whitespace collapsed first, then cut at [clip] characters with
 * an ellipsis appended, so a clipped fragment is visibly a fragment. The budget is
 * [RunPolicy.judgmentResultClip] when the run renders this itself.
 *
 * @param clip The most characters of any one step's result that reach the state.
 * @throws IllegalArgumentException when [clip] is not positive.
 */
@AmpereStableApi
fun RunObservation.asDecisionState(clip: Int): String {
    require(clip > 0) { "a clipped result has at least one character; got $clip" }

    val state = buildJsonObject {
        put("goal", goal.planStepDescription ?: goal.id)
        put("cycle", cycle)
        putJsonArray("results") {
            priorResults.forEach { outcome ->
                addJsonObject {
                    put("step", outcome.stepDescription)
                    put("status", outcome.statusWord())
                    put("origin", outcome.originWord())
                    put("result", outcome.reportedText().clipTo(clip))
                }
            }
        }
    }
    // `JsonElement.toString()` is the compact, correctly escaped rendering of the tree
    // it was built as, in insertion order — which is the fixed order this function's
    // whole contract rests on, so there is nothing for a `Json` instance's
    // configuration to come between.
    return state.toString()
}

/**
 * The one word a decision model reads a step's outcome as.
 *
 * `internal`, with [originWord] and [reportedText], because [RunDrift.asDecisionState]
 * renders a cycle's results by the same three words (AMPR-481): two vocabularies for one
 * outcome would be two things to keep in step.
 *
 * One word per outcome, including the one the ticket's three did not name:
 * a [StepOutcome.Skipped] step is not a failed step, and calling it one would have the
 * model judge a run against work it was never asked to do (the walk skips what came
 * after a critical failure, which is already reported as that failure). A
 * [StepOutcome.Held] step is its own word for the same reason (AMPR-425), although the
 * run itself never asks about a held cycle.
 */
internal fun StepOutcome.statusWord(): String = when (this) {
    is StepOutcome.Success -> "succeeded"
    is StepOutcome.PartialSuccess -> "partial"
    is StepOutcome.Failure -> "failed"
    is StepOutcome.Held -> "held"
    is StepOutcome.Skipped -> "skipped"
}

/**
 * Where the step's text came from, in the words the step-results block labels it with
 * (AMPR-453). The decision model reads the result next to it, so it is told whether that
 * result is the run's own or outside text before it reads it.
 */
internal fun StepOutcome.originWord(): String = when (origin) {
    ContentOrigin.Own -> "own"
    ContentOrigin.External -> "external"
}

/** What this step says about itself: its details, its error, what it waits on, or why it was skipped. */
internal fun StepOutcome.reportedText(): String = when (this) {
    is StepOutcome.Success -> details
    is StepOutcome.PartialSuccess -> details
    is StepOutcome.Failure -> error
    is StepOutcome.Held -> reason
    is StepOutcome.Skipped -> reason
}
