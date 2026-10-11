package link.socket.ampere.field

import kotlin.jvm.JvmInline

/**
 * Identity of an [Action]. An Action is a record about primitives, not a [Primitive],
 * so it cannot be the target of a [Ref]; it gets its own handle instead.
 */
@JvmInline
value class ActionId(val value: String)

/**
 * A transition the Field may undergo: proposed by a Subject, admitted by the Breaker,
 * taken by an executor.
 *
 * Plan steps are the model's proposed Actions — there is no separate candidate type
 * (F16). Escalation to a human is an Action like any other. What makes an Action
 * rankable is the pair of [Potential] and [Resistance] the fold derives for it; neither
 * is stored on the Action, because both are signals.
 *
 * @property id Identity of this Action within the Field.
 * @property actor The Subject that *decided* — proposed — the Action.
 * @property executor The `Executor.id` that performs it, or `null` while unassigned.
 *   Deliberately a separate member from [actor] (F17): decider and performer sharing one
 *   string is what made "who did this" unanswerable. The *wiring* that fills it from
 *   the live path is W3; the type carries both fields now so nothing in W2 has to be
 *   migrated when it lands. Typed as `String` because `Executor` lives in
 *   `agents.execution`, outside this leaf.
 * @property goal The Goal the Action serves, when known.
 * @property description What the Action does, as prose.
 * @property state Where in `Proposed → Admitted → Taken` it is; see [ActionState].
 * @property reversibility How hard it is to undo; see [Reversibility]. Reversibility
 *   sets the confidence floor below which an Action escalates instead of running.
 * @property cost What taking it is expected to spend; see [Cost].
 * @property prediction What the actor expects to happen, if it said; see [Prediction].
 * @property provenance The event that proposed the Action.
 */
data class Action(
    val id: ActionId,
    val actor: Ref<Subject>,
    val executor: String? = null,
    val goal: Ref<Goal>? = null,
    val description: String,
    val state: ActionState,
    val reversibility: Reversibility,
    val cost: Cost,
    val prediction: Prediction? = null,
    val provenance: Provenance,
)

/**
 * The three states an [Action] passes through, in order. The order is the only
 * transition rule: an Action moves forward one state at a time and never back. A
 * rejected proposal does not get a state; it stops being a candidate.
 */
enum class ActionState {

    /** Named by an actor; not yet checked against Constraints. */
    PROPOSED,

    /** Passed the Breaker; eligible to be taken. */
    ADMITTED,

    /** Performed by the executor. */
    TAKEN,
}

/**
 * How hard an [Action] is to undo.
 *
 * Three values rather than a number because the question the loop asks is categorical:
 * is this cheap enough to try without asking, or must confidence clear a floor first?
 */
enum class Reversibility {

    /** Undone by a routine inverse — an edit in a worktree, a draft reply. */
    REVERSIBLE,

    /** Undoable, at a cost worth weighing — a pushed commit, a sent message to a team. */
    COSTLY,

    /** Not undoable — a published release, a deleted branch, a message to a stranger. */
    IRREVERSIBLE,
}

/**
 * What taking an [Action] is expected to spend, in Watts — the unit the trace already
 * accounts model cost in (`WattCost`). One number: a cost in a second unit is a second
 * ranking axis, and F28 has not opened one.
 */
data class Cost(val watts: Double)

/**
 * What the actor expects an [Action] to produce, and how sure it is.
 *
 * `1 − confidence` is the Uncertain-degeneracy detector's input (F22) when the
 * confidence is *measured*; a self-reported value is acceptable only after the F10
 * mapping and is never band-fitted. The number here is already mapped, as on
 * [Belief.Settled.confidence].
 *
 * @property outcome The expected result, as prose.
 * @property confidence In `[0, 1]`.
 */
data class Prediction(
    val outcome: String,
    val confidence: Double,
)
