package link.socket.ampere.eval.decide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.propel.RunQuestions

/**
 * The two question forms the goal-met bench asks (AMPR-422 task 4).
 *
 * The shipped form is [RunQuestions.goalMet], a `noul`, exactly as a hosted run asks it. The
 * second is the same wording as a three-option `choice` — `met`, `not_met`, `cannot_tell` —
 * because the write-gate measurement found the form matters. `cannot_tell` is counted as not
 * met: a run cannot stop on it.
 *
 * The choice's texts are *derived* from the shipped noul rather than written here, so a change
 * to `RunQuestions` moves both forms together and a reworded question is still one question
 * reworded, not two drifting apart.
 */
enum class GoalMetForm(val question: Question, val metKey: String, val unmetKey: String) {

    /** The shipped `RunQuestions.goalMet`. */
    Noul(
        question = RunQuestions.goalMet,
        metKey = Question.Noul.TRUE_KEY,
        unmetKey = Question.Noul.FALSE_KEY,
    ),

    /** The same question as a three-option choice; `cannot_tell` reads as not met. */
    Choice(
        question = Question.Choice(
            instructions = RunQuestions.goalMet.instructions,
            criteria = linkedMapOf(
                MET_KEY to RunQuestions.goalMet.criteria.getValue(Question.Noul.TRUE_KEY),
                NOT_MET_KEY to RunQuestions.goalMet.criteria.getValue(Question.Noul.FALSE_KEY),
                CANNOT_TELL_KEY to "The step results do not show enough to say whether the goal was achieved",
            ),
        ),
        metKey = MET_KEY,
        unmetKey = NOT_MET_KEY,
    ),
    ;

    /** The answer keys a report reads as "met" for this form. */
    val positiveKeys: Set<String>
        get() = setOf(metKey)

    /** The wire name of the form, for an arm id or a file name. */
    val wireName: String
        get() = name.lowercase()

    /**
     * [case], asked in this form: its question swapped and its label translated. A case is
     * authored in [Noul] form; this is how the choice form is derived from the same corpus.
     */
    fun of(case: DecisionCase): DecisionCase = case.copy(
        question = question,
        expected = if (case.expected in Noul.positiveKeys || case.expected == MET) metKey else unmetKey,
    )

    companion object {
        const val MET: String = MET_KEY
        const val NOT_MET: String = NOT_MET_KEY
        const val CANNOT_TELL: String = CANNOT_TELL_KEY
    }
}

// File-level, because an enum entry's initializer runs before the enum's companion exists.
private const val MET_KEY = "met"
private const val NOT_MET_KEY = "not_met"
private const val CANNOT_TELL_KEY = "cannot_tell"

/**
 * The two rules a run uses today, as deterministic arms (AMPR-422 task 3).
 *
 * Both read the state the way a decision model does — the rendered JSON — and neither reads
 * the text of any result. That is the point: they are the baseline a model has to beat, and
 * they are exactly as blind as the code they stand for.
 */
object GoalMetArms {

    /** The Arc path's rule (`FlowPhase.evaluateGoalCompletion`): met when at least one step succeeded. */
    const val ANY_SUCCESS: String = "any-success"

    /** The hosted run's rule (`RosterHostedRun.currentStatus`): met when no step failed. */
    const val NO_FAILURE: String = "no-failure"

    val anySuccess: DeterministicDecisionClient = DeterministicDecisionClient(ANY_SUCCESS) { state, question ->
        answer(question, met = statuses(state).any { it == SUCCEEDED })
    }

    val noFailure: DeterministicDecisionClient = DeterministicDecisionClient(NO_FAILURE) { state, question ->
        answer(question, met = statuses(state).none { it == FAILED })
    }

    /** The rules by name. */
    val all: Map<String, DeterministicDecisionClient> = mapOf(ANY_SUCCESS to anySuccess, NO_FAILURE to noFailure)

    private const val SUCCEEDED = "succeeded"
    private const val FAILED = "failed"

    private val json = Json { ignoreUnknownKeys = true }

    /** The `status` word of every entry of the state's `results`, in order. */
    fun statuses(state: String): List<String> = json.parseToJsonElement(state)
        .jsonObject
        .getValue("results")
        .jsonArray
        .map { it.jsonObject.getValue("status").jsonPrimitive.content }

    /** The key meaning [met] (or not) for [question], whichever form it is in. */
    fun answer(question: Question, met: Boolean): String = when (question) {
        is Question.Noul -> if (met) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY
        is Question.Choice -> when {
            met -> GoalMetForm.MET.takeIf { it in question.options } ?: question.options.first()
            else -> GoalMetForm.NOT_MET.takeIf { it in question.options } ?: question.options.last()
        }
        is Question.Score -> if (met) question.levels.last() else question.levels.first()
    }
}
