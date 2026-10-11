package link.socket.ampere.propel

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.llm.decide.Question

/**
 * What a hosted run asks about itself (AMPR-421).
 *
 * One question, and the id its records are filed under. The run asks it on the host
 * seat after each executed cycle when [RunPolicy.goalCheck] is [GoalCheck.Record], and
 * the answer is recorded and never read: nothing in the loop, in
 * [RunResult.status][RunResult] or in LEARN consults it. That is deliberate — whether
 * a model can answer this well enough for the run to *act* on it is what AMPR-422
 * measures, and the records left here are what it measures against.
 *
 * A consumer asking its own questions declares them on the verifier seat
 * ([HostedAgent.questions]) rather than adding to this object: this one is the run's,
 * phrased about the state the run renders, and its id is reserved.
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
}

/**
 * This observation as the state of a decision call: compact JSON, in a fixed key order
 * (AMPR-421).
 *
 * ```json
 * {"goal":"Add a retry","cycle":1,"results":[{"step":"Search","status":"succeeded","result":"found it"}]}
 * ```
 *
 * The key order is fixed, and the only things rendered are the goal's description, the
 * cycle, and per step its description, a status word and what it reported. A step's id
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
 * Four words for four outcomes, including the one the ticket's three did not name:
 * a [StepOutcome.Skipped] step is not a failed step, and calling it one would have the
 * model judge a run against work it was never asked to do (the walk skips what came
 * after a critical failure, which is already reported as that failure).
 */
private fun StepOutcome.statusWord(): String = when (this) {
    is StepOutcome.Success -> "succeeded"
    is StepOutcome.PartialSuccess -> "partial"
    is StepOutcome.Failure -> "failed"
    is StepOutcome.Skipped -> "skipped"
}

/** What this step says about itself: its details, its error, or why it was skipped. */
private fun StepOutcome.reportedText(): String = when (this) {
    is StepOutcome.Success -> details
    is StepOutcome.PartialSuccess -> details
    is StepOutcome.Failure -> error
    is StepOutcome.Skipped -> reason
}
