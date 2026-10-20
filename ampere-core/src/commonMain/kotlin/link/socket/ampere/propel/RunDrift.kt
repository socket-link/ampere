package link.socket.ampere.propel

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.field.Action
import link.socket.ampere.field.ActionResult
import link.socket.ampere.field.Belief
import link.socket.ampere.field.FieldDelta
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity

/**
 * What the receptor is asked about after an executed cycle (AMPR-481, epic AMPR-478 row N6).
 *
 * The state the two receptor questions — [RunQuestions.perceptionDrift],
 * [RunQuestions.lessonWorthKeeping] — are asked over, in their own request: the goal, the
 * cycle, what the host seat last perceived, what moved in the Field since ([delta], the fold
 * AMPR-479 makes of the PERCEIVE snapshot and the after-cycle one AMPR-480 takes), and what
 * this cycle's steps reported. A value the run builds inside the OBSERVE bracket and drops;
 * nothing stores it and no event carries it.
 *
 * Its own type rather than a field on [RunObservation], so the goal question's request, state
 * and digests stay exactly what AMPR-421 made them and the corpus AMPR-422 measures against is
 * untouched.
 *
 * @property runId The run. Rendered nowhere: see [asDecisionState].
 * @property goal The run's goal.
 * @property cycle Which executed cycle this is, counting from 1.
 * @property perceived The host seat's last PERCEIVE idea, so a model can weigh the changes
 *   against the reading they would revise. Empty when PERCEIVE produced none.
 * @property delta What moved in the Field between the two snapshots. The empty delta when
 *   the run observes no Field or a snapshot failed: the lesson question is still asked over
 *   the cycle's results, and `changes` renders with every list empty.
 * @property cycleResults The outcomes of the steps this cycle executed — this cycle's, not
 *   every cycle's, which is what [RunObservation.priorResults] carries.
 * @property names What the Field calls the primitives the delta refers to, keyed by
 *   [Ref.value]: a Subject's name, a Goal's description. A delta names a committer, a holder,
 *   an actor or a belief's subject by reference only, so the run passes the lookup it has —
 *   the later snapshot's subjects and goals. A reference with no name renders as its value.
 * @property rootGoal The run's own goal on the Goal branch (AMPR-473), which a changed goal's
 *   `relation` is read against (AMPR-486); null for a run with no branch, whose goal changes
 *   all render `other`. A reference, never rendered: see [asDecisionState].
 */
@AmpereStableApi
data class RunDrift(
    val runId: RunId,
    val goal: Task,
    val cycle: Int,
    val perceived: String,
    val delta: FieldDelta,
    val cycleResults: List<StepOutcome>,
    val names: Map<String, String> = emptyMap(),
    val rootGoal: Ref<Goal>? = null,
)

/**
 * This drift as the state of a decision call: compact JSON, in a fixed key order (AMPR-481,
 * row N6).
 *
 * ```json
 * {"goal":"…","cycle":2,"perceived":"…",
 *  "changes":{"goals":[{"goal":"…","from":"OPEN","to":"COMPLETED","relation":"dependency"}],
 *   "commitments":{"opened":[{"by":"…","on":"…","due":"2026-10-21","about":"…"}],"resolved":[]},
 *   "beliefs":[{"kind":"lesson","holder":"…","about":"…","content":"…"}],
 *   "observations":[{"about":"…","content":"…"}],
 *   "actions":[{"by":"…","did":"…","result":"succeeded"}],
 *   "subjects":["…"],"protected_left_out":1},
 *  "cycle_results":[{"step":"…","status":"succeeded","origin":"external","result":"…"}]}
 * ```
 *
 * The key order is fixed, and nothing rendered is an id or a timestamp — not the run id, not
 * a goal's, a step's or an Action's id, not a provenance, not a sequence number; a
 * commitment's `due` is a date, the one time that is part of what was undertaken — for the
 * reason [RunObservation.asDecisionState] gives: the record's
 * [stateDigest][link.socket.ampere.llm.decide.stateDigest] is the only join key back to the
 * state, so two reads of one moment have to render one string.
 *
 * **No `PROTECTED` row is rendered.** A `PROTECTED` belief or Observation is about a real
 * person (F12, AMPR-381 J11), and the state goes to a decision model off the device, so such
 * a row is counted into `protected_left_out` — [FieldDelta.protectedLeftOut], which the fold
 * already counts — and nothing it holds reaches the text: not its content, its subject or its
 * holder. There is no setting that turns this off.
 *
 * What each branch renders, every text through [clipTo] at [clip]: a changed goal by its
 * description, its two states and its `relation` to the run's goal; a commitment by its
 * committer's name, its `ticketId`, the ISO date of its `due` in UTC and, when it is on a goal,
 * that goal's description as `about`; a belief by its kind lower-cased, its holder's name, what
 * it is about and its content; an observation by what it is about and its content; an Action
 * by its actor's name, its description and `succeeded` / `failed` (`pending` for one not yet
 * taken); a subject by its name; and a cycle result by the same `statusWord`, `originWord`
 * and `reportedText` the goal question's state uses, so one outcome reads the same in both.
 * Relationships added are not rendered: an edge is bookkeeping to a receptor, and the
 * subjects it joins are already in `subjects`.
 *
 * **The structure a change carries is rendered as its relation to the run (AMPR-486).** A judge
 * asked whether a goal's move or a commitment bears on the run cannot infer a link the state
 * leaves out, so the two structural branches say it in words:
 *
 * - a changed goal's `relation` is one of three — `own` when it is [RunDrift.rootGoal];
 *   `dependency` when the run's goal depends on it, which the later snapshot carries as the
 *   changed goal's [Goal.parent] being the run's goal (the Field holds no `Rule.Dependency`
 *   yet, so a parent is the one dependency edge there is to read); `other` otherwise, and for
 *   every change of a run with no [RunDrift.rootGoal]. The set is closed.
 * - a commitment's `about` is the description of the goal it is on, through the same [RunDrift.names]
 *   lookup as every other reference. A commitment on no goal has no `about` key at all, so a state
 *   with no goal-bound commitment renders exactly as it did before the key existed.
 *
 * @param clip The most characters of any one text that reach the state.
 * @throws IllegalArgumentException when [clip] is not positive.
 */
@AmpereStableApi
fun RunDrift.asDecisionState(clip: Int): String {
    require(clip > 0) { "a clipped result has at least one character; got $clip" }

    val nameOf: (Ref<Primitive>) -> String = { ref -> (names[ref.value] ?: ref.value).clipTo(clip) }

    val state = buildJsonObject {
        put("goal", goal.planStepDescription ?: goal.id)
        put("cycle", cycle)
        put("perceived", perceived.clipTo(clip))
        putJsonObject("changes") {
            putJsonArray("goals") {
                delta.goalsChanged.forEach { change ->
                    addJsonObject {
                        put("goal", change.goal.description.clipTo(clip))
                        put("from", change.from.name)
                        put("to", change.to.name)
                        put("relation", relationOf(change.goal))
                    }
                }
            }
            putJsonObject("commitments") {
                putJsonArray("opened") {
                    delta.commitmentsOpened.forEach { commitment ->
                        addJsonObject {
                            put("by", nameOf(commitment.committer))
                            put("on", commitment.ticketId.clipTo(clip))
                            put("due", commitment.due.toLocalDateTime(TimeZone.UTC).date.toString())
                            commitment.goal?.let { goal -> put("about", nameOf(goal)) }
                        }
                    }
                }
                putJsonArray("resolved") {
                    delta.commitmentsResolved.forEach { commitment ->
                        addJsonObject {
                            put("by", nameOf(commitment.committer))
                            put("on", commitment.ticketId.clipTo(clip))
                            put("due", commitment.due.toLocalDateTime(TimeZone.UTC).date.toString())
                            commitment.goal?.let { goal -> put("about", nameOf(goal)) }
                        }
                    }
                }
            }
            putJsonArray("beliefs") {
                delta.beliefsAdded.filterNot { it.isProtected }.forEach { belief ->
                    addJsonObject {
                        put("kind", belief.kind.name.lowercase())
                        put("holder", nameOf(belief.holder))
                        put("about", nameOf(belief.subject))
                        put("content", belief.content.clipTo(clip))
                    }
                }
            }
            putJsonArray("observations") {
                delta.observationsAdded.filterNot { it.isProtected }.forEach { observation ->
                    addJsonObject {
                        put("about", nameOf(observation.subject))
                        put("content", observation.content.clipTo(clip))
                    }
                }
            }
            putJsonArray("actions") {
                delta.actionsByOthers.forEach { action ->
                    addJsonObject {
                        put("by", nameOf(action.actor))
                        put("did", action.description.clipTo(clip))
                        put("result", action.resultWord())
                    }
                }
            }
            putJsonArray("subjects") {
                delta.subjectsAdded.forEach { subject -> add(subject.name.clipTo(clip)) }
            }
            put("protected_left_out", delta.protectedLeftOut)
        }
        putJsonArray("cycle_results") {
            cycleResults.forEach { outcome ->
                addJsonObject {
                    put("step", outcome.stepDescription)
                    put("status", outcome.statusWord())
                    put("origin", outcome.originWord())
                    put("result", outcome.reportedText().clipTo(clip))
                }
            }
        }
    }
    // `JsonElement.toString()` is the compact, correctly escaped rendering of the tree in
    // insertion order — the fixed order this function's contract rests on — exactly as
    // `RunObservation.asDecisionState` relies on it.
    return state.toString()
}

/**
 * How a changed [goal] relates to this run's goal, in one of three words (AMPR-486): `own`,
 * `dependency` — the run's goal depends on it, read as [goal]'s parent being the run's goal —
 * or `other`.
 */
private fun RunDrift.relationOf(goal: Goal): String = when {
    rootGoal == null -> "other"
    goal.id == rootGoal -> "own"
    goal.parent == rootGoal -> "dependency"
    else -> "other"
}

private val Belief.isProtected: Boolean
    get() = sensitivity == Sensitivity.PROTECTED

/** The one word a decision model reads an Action's outcome as. */
private fun Action.resultWord(): String = when (result) {
    ActionResult.Succeeded -> "succeeded"
    is ActionResult.Failed -> "failed"
    null -> "pending"
}

/**
 * The delta a run renders when it has no two snapshots to fold (AMPR-481): every list empty,
 * nothing left out. `FieldDelta`'s own empty fold is private to the fold, and this leaf is
 * not the Field's to change.
 */
internal fun noDrift(): FieldDelta = FieldDelta(
    fromSequence = 0,
    toSequence = 0,
    goalsChanged = emptyList(),
    commitmentsOpened = emptyList(),
    commitmentsResolved = emptyList(),
    beliefsAdded = emptyList(),
    observationsAdded = emptyList(),
    actionsByOthers = emptyList(),
    subjectsAdded = emptyList(),
    relationshipsAdded = emptyList(),
    protectedLeftOut = 0,
)
