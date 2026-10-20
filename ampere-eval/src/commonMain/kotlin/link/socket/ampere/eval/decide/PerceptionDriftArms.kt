package link.socket.ampere.eval.decide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.Question

/**
 * The two deterministic references for the perception-drift question (AMPR-482 task 2).
 *
 * Both read the state the way a decision model does — the rendered JSON of
 * `RunDrift.asDecisionState` — and neither reads the text of any change. They are the
 * baseline a model has to beat and the rule a consumer could ship with no judge at all:
 *
 * - [emptyOnly] answers *bears* unless `changes` is empty. It is the rule "re-perceive whenever
 *   anything moved" and can never be false-settled, since a bears case has, by construction,
 *   something under `changes`.
 * - [ownAndCosmetic] answers *bears* unless the delta is empty, is only this run's own actions,
 *   or is a cosmetic re-render. On a production state the second and third clauses are already
 *   the first: `FieldDelta.between` leaves every own seat's Actions out of `actionsByOthers`
 *   (AMPR-479) and keys every list on identity, so a re-read of the same Field in another order
 *   folds to the empty delta. The rule is kept as the ticket named it, with the own clause
 *   reading the `by` of each action against [ownNames], and the bench shows where the two
 *   rules agree — which on this corpus is everywhere.
 */
object PerceptionDriftArms {

    /** Bears unless `changes` is empty. */
    const val EMPTY_ONLY: String = "empty-only"

    /** Bears unless `changes` is empty, is own actions only, or is a cosmetic re-render. */
    const val OWN_AND_COSMETIC: String = "own-and-cosmetic"

    val emptyOnly: DeterministicDecisionClient = DeterministicDecisionClient(EMPTY_ONLY) { state, question ->
        answer(question, bears = !changes(state).isEmpty)
    }

    /**
     * The own-and-cosmetic rule, with [ownNames] the names the state renders this run's own
     * seats as — what `RunDrift.names` maps the seat Subjects to.
     */
    fun ownAndCosmetic(ownNames: Set<String>): DeterministicDecisionClient =
        DeterministicDecisionClient(OWN_AND_COSMETIC) { state, question ->
            val changes = changes(state)
            answer(question, bears = !(changes.isEmpty || changes.isOwnActionsOnly(ownNames)))
        }

    /** The rules by name, for a run over [ownNames]. */
    fun all(ownNames: Set<String>): Map<String, DeterministicDecisionClient> =
        mapOf(EMPTY_ONLY to emptyOnly, OWN_AND_COSMETIC to ownAndCosmetic(ownNames))

    /**
     * The `changes` object of a rendered state, as counts and the actors of its actions — the
     * only things the two rules read.
     */
    data class Changes(
        val goals: Int,
        val commitmentsOpened: Int,
        val commitmentsResolved: Int,
        val beliefs: Int,
        val observations: Int,
        val actionsBy: List<String>,
        val subjects: Int,
        val protectedLeftOut: Int,
    ) {
        /** True when every list is empty. A protected row left out is not a change the rule can read. */
        val isEmpty: Boolean
            get() = goals == 0 && commitmentsOpened == 0 && commitmentsResolved == 0 && beliefs == 0 &&
                observations == 0 && actionsBy.isEmpty() && subjects == 0

        /** True when the only thing that moved is actions, every one of them by a name in [ownNames]. */
        fun isOwnActionsOnly(ownNames: Set<String>): Boolean =
            actionsBy.isNotEmpty() && actionsBy.all { it in ownNames } &&
                goals == 0 && commitmentsOpened == 0 && commitmentsResolved == 0 && beliefs == 0 &&
                observations == 0 && subjects == 0
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The `changes` of [state]. */
    fun changes(state: String): Changes {
        val changes = json.parseToJsonElement(state).jsonObject.getValue("changes").jsonObject
        val commitments = changes.getValue("commitments").jsonObject
        return Changes(
            goals = changes.getValue("goals").jsonArray.size,
            commitmentsOpened = commitments.getValue("opened").jsonArray.size,
            commitmentsResolved = commitments.getValue("resolved").jsonArray.size,
            beliefs = changes.getValue("beliefs").jsonArray.size,
            observations = changes.getValue("observations").jsonArray.size,
            actionsBy = changes.getValue("actions").jsonArray.map { action ->
                action.jsonObject.getValue("by").jsonPrimitive.content
            },
            subjects = changes.getValue("subjects").jsonArray.size,
            protectedLeftOut = changes.getValue("protected_left_out").jsonPrimitive.int,
        )
    }

    /** The key meaning [bears] (or settled) for [question], whichever form it is in. */
    fun answer(question: Question, bears: Boolean): String = when (question) {
        is Question.Noul -> if (bears) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY
        is Question.Choice -> when {
            bears -> PerceptionDriftForm.BEARS.takeIf { it in question.options } ?: question.options.first()
            else -> PerceptionDriftForm.BESIDE.takeIf { it in question.options } ?: question.options.last()
        }
        is Question.Score -> if (bears) question.levels.last() else question.levels.first()
    }
}
