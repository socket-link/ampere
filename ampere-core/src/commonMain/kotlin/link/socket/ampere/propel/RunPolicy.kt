package link.socket.ampere.propel

import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.memory.RecallSource

/**
 * What a consumer declares about how its run is driven (AMPR-385 row H1).
 *
 * Everything on it has a default, and the defaults are the cheapest honest run: one
 * cycle, a LEARN that bills nothing, a gate that approves what the host planned, and
 * a RECALL that reads the seat's own memory and nothing else.
 * A consumer that wants more says so.
 *
 * @property cycles How many times the host seat may plan and execute. The default of
 *   1 is one pass: PERCEIVE → RECALL → OBSERVE → PLAN → EXECUTE → LEARN. Above 1 the
 *   run re-enters OBSERVE → PLAN after EXECUTE with the results in context, and stops
 *   early when the planner returns no steps (AMPR-385 row H17) or a step is held
 *   waiting on a person (AMPR-425). The cap is the consumer's, because the consumer is
 *   the one quoting for it.
 * @property learn Whether LEARN spends a model call. See [LearnPolicy].
 * @property planGate Who approves the plan before EXECUTE runs it. The gate rides
 *   here rather than on `RunHost.open`'s parameter list so that list stays the one
 *   row H1 locked.
 * @property recall Which corpora RECALL reads besides the seat's memory, and how much
 *   of what it finds reaches the prompt. See [RecallPolicy].
 * @property goalCheck Whether the run asks itself, after each executed cycle, whether
 *   the goal is met. See [GoalCheck]; the default asks nothing and bills nothing.
 * @property judgmentResultClip How many characters of any one step's result reach the
 *   state a decision call is given ([asDecisionState]). Only read when something is
 *   asked. Longer text is cut there and an ellipsis appended, so a decision model sees
 *   that a result was a fragment.
 * @property quotas The run's token, wall-clock and step ceilings (AMPR-445). Each one set
 *   is a Hard rule the Breaker admits every step against, read from the run's persisted
 *   events. The default sets none. See [RunQuotas].
 * @property maxStepsPerCycle The most steps one cycle may execute, or null — the
 *   default — for no cap (AMPR-452, row H20 (c)). Set, it bounds a plan two ways
 *   before the [planGate] sees it: no more than this many steps, and no step naming a
 *   tool that no seat of the run has. A plan that breaks either goes back to the host
 *   seat's planner **once**, told what was wrong — and that re-plan is a second PLAN
 *   call on the host seat, **billed** like the first. If the second plan still breaks
 *   a rule it is cut: steps naming unknown tools are dropped, then the rest is cut to
 *   the cap, and a `PlanEvent.PlanTruncated` goes on the record before the gate is
 *   asked. A plan cut to nothing is not sent to the gate and ends the run. Null checks
 *   nothing, re-plans nothing and publishes nothing, so a run without a cap is the
 *   run it was before the cap existed — and a step naming an unknown tool still fails
 *   at dispatch, as it always has.
 * @property observe Whether the run reads the Field, and how much of it the host seat's
 *   PERCEIVE is shown (AMPR-480, rows N1 and N10). See [ObservePolicy]; the default reads
 *   nothing and renders nothing.
 * @property cadence When PERCEIVE, RECALL and LEARN run again inside a run (AMPR-481, row N4
 *   of the continuous-PROPEL epic; AMPR-483, rows N3–N5). See [PhaseCadence]; the default is
 *   the run as it was — each once. `Record` values ask the receptor's two questions and move
 *   nothing; [PerceiveCadence.WhenDrifted] and [PerceiveCadence.EveryCycle] re-enter the next
 *   cycle at PERCEIVE, and [RecallCadence.WithPerceive] re-runs RECALL beside it;
 *   [LearnCadence.WhenLesson] and [LearnCadence.EveryCycle] let a successful cycle store its
 *   deterministic lesson before the loop decides (AMPR-484, row N8).
 */
@AmpereStableApi
data class RunPolicy(
    val cycles: Int = 1,
    val learn: LearnPolicy = LearnPolicy.Deterministic,
    val planGate: PlanGate = PlanGate.Pass,
    val recall: RecallPolicy = RecallPolicy(),
    val goalCheck: GoalCheck = GoalCheck.Off,
    val judgmentResultClip: Int = 400,
    val quotas: RunQuotas = RunQuotas.None,
    val maxStepsPerCycle: Int? = null,
    val observe: ObservePolicy = ObservePolicy(),
    val cadence: PhaseCadence = PhaseCadence(),
) {

    init {
        require(cycles >= 1) { "a run runs at least one cycle; got $cycles" }
        require(judgmentResultClip > 0) {
            "a clipped result has at least one character; got $judgmentResultClip"
        }
        require(maxStepsPerCycle == null || maxStepsPerCycle >= 1) {
            "a capped cycle runs at least one step; got $maxStepsPerCycle"
        }
    }
}

/**
 * Whether a hosted run asks whether it met its goal (AMPR-421, AMPR-381 row J9).
 *
 * ## What it costs
 *
 * Stated here because the consumer is the one quoting for it, and because a decision
 * call bills on a transport the consumer supplied:
 *
 * - [Record] is **one decision call per executed cycle**, on the host seat's
 *   [HostedAgent.upstreamDecisionClient], carrying one question.
 * - A verifier seat that declared [HostedAgent.questions] is **one more decision call
 *   per executed cycle**, on *that* seat's transport, carrying all of its questions in
 *   one request. That is opted into by declaring the questions, independently of this
 *   enum: a run can ask the verifier's questions with the goal check [Off].
 *
 * Neither is a generative call, so neither shows up in the run's prompts or in its
 * `ProviderCall*` pairs. A cycle that did not execute — stopped at the gate, planned
 * into nothing, cancelled — asks nothing.
 */
@AmpereStableApi
enum class GoalCheck {

    /**
     * Ask nothing. The default, and the only setting under which a hosted run's event
     * stream is what it was before AMPR-421, event for event.
     */
    Off,

    /**
     * Ask [RunQuestions.goalMet] on the host seat after each executed cycle, record the
     * answer, and change nothing.
     *
     * Record-only, deliberately: the run does not stop early on a `true`, does not
     * re-plan on a `false`, and [RunResult] is identical whatever came back. Acting on
     * the answer is AMPR-381 row J9 and waits on AMPR-422 — whether a model can answer
     * this well enough to be allowed to end a run — which is the measurement these
     * records exist to feed.
     */
    Record,
}

/**
 * When the three phases a hosted run charges or closes with run again inside the run
 * (AMPR-481, epic AMPR-478 row N4; AMPR-483, rows N3–N5; AMPR-484, row N8).
 *
 * A hosted run perceives once at `open`, recalls once beside it, and closes with a LEARN at
 * `close()` (hosted-run invariant 7). The continuous loop re-runs PERCEIVE when the world
 * has drifted and LEARN when a cycle produced a lesson worth keeping — and whether either
 * has happened is a judgment, asked of the host seat's decision transport after each
 * executed cycle. This value says what the run does with that judgment. The `Record` values
 * **record and never act**; [PerceiveCadence.WhenDrifted] acts on the drift judgment in the
 * add-only direction — the next cycle re-enters at PERCEIVE rather than at OBSERVE — and
 * [PerceiveCadence.EveryCycle] re-enters there without asking. [LearnCadence.WhenLesson] acts
 * on the lesson judgment the same way — a successful cycle stores its deterministic lesson
 * before the loop decides, and the close-time LEARN is untouched — and
 * [LearnCadence.EveryCycle] stores it without asking. The interfaces are sealed rather than
 * enums so each acting value arrives without a breaking change.
 *
 * ## What it costs
 *
 * Stated here because the consumer is the one quoting for it, and because a decision call
 * bills on a transport the consumer supplied:
 *
 * - [PerceiveCadence.Record] and [LearnCadence.Record] together are **one decision call per
 *   executed cycle**, on the host seat's [HostedAgent.upstreamDecisionClient], carrying up to
 *   two questions ([RunQuestions.perceptionDrift], [RunQuestions.lessonWorthKeeping]) in
 *   one request over one state ([RunDrift.asDecisionState]). Either alone is the same one
 *   call carrying one question. It is a request of its own: [GoalCheck.Record]'s question
 *   keeps its own request and its own state, so its records are what they were.
 * - [PerceiveCadence.WhenDrifted] is the same **one decision call per executed cycle** — the
 *   receptor's, carrying the lesson question too when [LearnCadence.Record] is on — plus
 *   **one PERCEIVE model call** on the host seat for every cycle whose verdict was
 *   `Violated` or `Undetermined`, and nothing more on a `Holds`.
 * - [PerceiveCadence.EveryCycle] is **one PERCEIVE model call per executed cycle** with no
 *   judge and no decision call.
 * - [RecallCadence.WithPerceive] is one more RECALL — store reads and corpus queries, no model
 *   call — beside every PERCEIVE that re-runs. Under [PerceiveCadence.OncePerRun] and
 *   [PerceiveCadence.Record] it costs nothing, because PERCEIVE never re-runs.
 * - [LearnCadence.WhenLesson] is the same **one decision call per executed cycle** — the
 *   receptor's request, which carries the lesson question beside the drift question when
 *   that is asked too, so it adds no call of its own — plus **one knowledge row per seat
 *   that ran** in a successful cycle whose measured `p(true)` reached `keepAbove`. No model
 *   call: the row is `KnowledgeExtractor.extractDefault`'s, as at `close()`.
 * - [LearnCadence.EveryCycle] is **no decision call and no model call**: the same rows after
 *   every successful cycle, with no judge.
 *
 * A decision call is not a generative call, so it shows up in neither the run's prompts nor
 * its `ProviderCall*` pairs; a re-run PERCEIVE is a generative call and shows up in both. A
 * cycle that did not execute — stopped at the gate, planned into nothing, held, cancelled —
 * asks nothing and re-enters nowhere.
 *
 * @property perceive When PERCEIVE runs again. See [PerceiveCadence].
 * @property recall When RECALL runs again. See [RecallCadence].
 * @property learn When LEARN runs. See [LearnCadence].
 */
@AmpereStableApi
data class PhaseCadence(
    val perceive: PerceiveCadence = PerceiveCadence.OncePerRun,
    val recall: RecallCadence = RecallCadence.OncePerRun,
    val learn: LearnCadence = LearnCadence.AtClose,
)

/**
 * When a hosted run's PERCEIVE runs again (AMPR-481, row N4; AMPR-483, rows N3 and N5).
 *
 * Sealed rather than an enum so the acting value — [WhenDrifted] — carries its threshold
 * without a breaking change. See [PhaseCadence] for the cost.
 *
 * Phase order is fixed (propel-loop invariant): a cadence chooses *where the next cycle
 * starts* — at PERCEIVE, or at OBSERVE as before — and never reorders or repeats a phase
 * inside a cycle. A cycle that re-enters at PERCEIVE runs PERCEIVE, then RECALL when
 * [RecallCadence.WithPerceive] says so, then OBSERVE → PLAN → EXECUTE as every cycle does.
 */
@AmpereStableApi
sealed interface PerceiveCadence {

    /**
     * Perceive once, at `open`. The default, and the only value under which the run asks
     * nothing about drift: its event stream is what it was before AMPR-481, event for event.
     */
    data object OncePerRun : PerceiveCadence

    /**
     * Ask [RunQuestions.perceptionDrift] on the host seat after each executed cycle, record
     * the answer, and change nothing.
     *
     * The question is asked over [RunDrift]: what moved in the Field between the PERCEIVE
     * snapshot and the one OBSERVE takes after the cycle (AMPR-479, AMPR-480), so it needs a
     * Field to have drifted — `open` refuses this value with [FieldScope.Off] or on a host
     * with no `FieldService`, and refuses it on a host seat with no decision transport. A
     * cycle whose snapshot failed is asked only the lesson question, if that is on too, and
     * the run logs once that drift could not be asked.
     *
     * Record-only, deliberately: PERCEIVE does not re-run on a `true`, the plan is not
     * revisited on a `false`, and [RunResult] is identical whatever came back. The records
     * here are what the bench measures; [WhenDrifted] is the value that acts.
     */
    data object Record : PerceiveCadence

    /**
     * Re-enter the next cycle at PERCEIVE when the world has drifted since the last one
     * (AMPR-483, rows N3 and N5; the add-only direction of AMPR-381 J6).
     *
     * After each executed cycle the run's drift receptor — a `Probe<RunDrift>` whose verdict
     * is on the record as a `ProbeEvent.VerdictReached` beside the `JudgmentRecorded` the
     * decision call leaves — asks [RunQuestions.perceptionDrift] over the same [RunDrift]
     * [Record] asks over, and reads the measured probability of `true`:
     *
     * - at or above [moveAbove] the verdict is `Violated`, and the next cycle re-enters at
     *   PERCEIVE: one PERCEIVE model call on the host seat, shown the world as it is now, and
     *   a RECALL beside it under [RecallCadence.WithPerceive]. The rendered delta reaches that
     *   cycle's PLAN as the "Observed since the last cycle" block;
     * - below it the verdict is `Holds`, and the next cycle re-enters at OBSERVE exactly as
     *   under [OncePerRun] — event for event;
     * - a verdict the receptor could not reach — the transport threw, or answered with no
     *   measured distribution — is `Undetermined`, and the next cycle re-enters at PERCEIVE
     *   too: an unchecked cell is not trusted (convict-but-not-acquit). A delta with nothing
     *   in it is `Holds` with no call, and one whose only entries are `PROTECTED` rows is
     *   `Violated` with no call, since what moved cannot be shown to the judge.
     *
     * Add-only: no verdict ends the run, re-plans past the planner's empty answer, or touches
     * `RunResult.status`. A run whose quota has tripped does not re-perceive, and a run that
     * held, stopped, was declined or was cancelled never re-enters at all. `open` refuses this
     * value exactly where it refuses [Record]: a host seat with no decision transport, a policy
     * with [FieldScope.Off], a host with no `FieldService`.
     *
     * @property moveAbove The probability of `true` at or above which a cycle's drift counts.
     *   Strictly between 0 and 1, and **Ampere ships no default**: the number separating
     *   `Violated` from `Holds` is the consumer's (AMPR-422, Q5). AMPR-482's report — the
     *   receptor measured on the drift corpus — is where a starting value comes from; it is
     *   a starting value, not a constant of Ampere's.
     */
    data class WhenDrifted(val moveAbove: Double) : PerceiveCadence {

        init {
            require(moveAbove > 0.0 && moveAbove < 1.0) {
                "moveAbove is a probability strictly between 0 and 1; got $moveAbove"
            }
        }
    }

    /**
     * Re-enter every later cycle at PERCEIVE, with no judge (AMPR-483, row N5).
     *
     * One PERCEIVE model call per executed cycle on the host seat, shown the world as it is
     * now, and a RECALL beside it under [RecallCadence.WithPerceive]; no decision call, no
     * verdict, no record beyond the phase's own. When the run observes the Field, the delta
     * since the last PERCEIVE reaches the cycle's PLAN as the "Observed since the last
     * cycle" block. Needs no transport and no Field: `open` refuses nothing for it.
     *
     * Add-only, like [WhenDrifted]: a run whose quota has tripped does not re-perceive, and a
     * run that held, stopped, was declined or was cancelled never re-enters.
     */
    data object EveryCycle : PerceiveCadence
}

/**
 * When a hosted run's RECALL runs again (AMPR-481, row N4; AMPR-483, row N4).
 *
 * An enum, because RECALL has no threshold of its own: it follows PERCEIVE or it does not.
 */
@AmpereStableApi
enum class RecallCadence {

    /** Recall once, beside the PERCEIVE at `open`. The default. */
    OncePerRun,

    /**
     * Recall again whenever PERCEIVE runs again.
     *
     * Effective under [PerceiveCadence.WhenDrifted] and [PerceiveCadence.EveryCycle], where a
     * cycle can re-enter at PERCEIVE: RECALL then runs again inside that cycle, before OBSERVE,
     * with the same query and scopes as at `open` (store reads and corpus queries, no model
     * call), and leaves one more `KnowledgeRecalled` on the record. Under every other
     * [PerceiveCadence] PERCEIVE runs once, so RECALL does too, and this value changes
     * nothing.
     */
    WithPerceive,
}

/**
 * When a hosted run's LEARN runs (AMPR-481, row N4; AMPR-484, row N8).
 *
 * Sealed rather than an enum so the acting value — [WhenLesson] — carries its threshold
 * without a breaking change. See [PhaseCadence] for the cost.
 *
 * Whatever the value, the LEARN at `close()` is what it always was (hosted-run invariant 7):
 * one outcome row for the run, Knowledge only for a run that `Succeeded`, the model-backed
 * evaluation under [LearnPolicy.ModelBacked] there and only there. The acting values add a
 * LEARN that *closes a cycle*: after an executed cycle whose plan walk settled to a success,
 * before the loop decides whether to continue, each seat that ran a step in that cycle
 * stores one `Knowledge.FromOutcome` — `KnowledgeExtractor.extractDefault` over the cycle's
 * outcome, no model call — through its own `AgentMemoryService` under the run id, tagged
 * with its role and `cycle:<n>`. What a cycle stored is kept whatever the run's later end
 * (Miley's answer to row N8, 2026-10-20): a long run that fails on its last cycle no longer
 * loses what its earlier cycles found. A cycle that held, was stopped by the gate, was
 * declined, was cancelled, or whose walk did not settle to a success learns nothing.
 */
@AmpereStableApi
sealed interface LearnCadence {

    /**
     * Learn once, at `close()`, as hosted-run invariant 7 says. The default, the only
     * value under which the run asks nothing about lessons, and the only one under which
     * no cycle learns: the event stream is what it was before AMPR-481, event for event.
     */
    data object AtClose : LearnCadence

    /**
     * Ask [RunQuestions.lessonWorthKeeping] on the host seat after each executed cycle,
     * record the answer, and change nothing.
     *
     * Asked over [RunDrift] beside the drift question when that is on too, in the same
     * request; alone, over a drift whose `changes` are empty when the run observes no Field.
     * `open` refuses this value on a host seat with no decision transport.
     *
     * Record-only, deliberately: LEARN still runs once, at `close()`, whatever came back,
     * and [RunResult] is identical. [WhenLesson] is the value that acts.
     */
    data object Record : LearnCadence

    /**
     * Let a successful cycle store its lesson when the judge says it is worth keeping
     * (AMPR-484, row N8).
     *
     * After each executed cycle [RunQuestions.lessonWorthKeeping] is asked on the host seat
     * exactly as under [Record] — in the receptor's request, beside the drift question when
     * [PerceiveCadence.Record] or [PerceiveCadence.WhenDrifted] asks that too, so it adds no
     * call of its own — and the measured probability of `true` is read against [keepAbove]:
     *
     * - at or above it, when the cycle's plan walk settled to a success, the host seat opens
     *   one LEARN bracket and every seat that ran a step in that cycle stores one
     *   `Knowledge.FromOutcome` through its own memory, tagged `cycle:<n>`;
     * - below it, nothing is stored and the cycle goes on as under [Record];
     * - a lesson judgment that is missing — the transport threw, answered without it, or the
     *   drift receptor made no call because nothing moved — or that carries no measured
     *   distribution stores nothing. A lesson is a claim, and the quiet direction is the safe
     *   one: an unchecked lesson is not kept, where an unchecked drift re-perceives.
     *
     * Add-only beside the close-time LEARN: no cycle's row ends the run, changes
     * `RunResult.status`, or replaces the run's own LEARN at `close()`, which still writes
     * what it wrote — so a `Succeeded` run under this value holds a cycle row and a close row
     * for the same seat. `open` refuses this value on a host seat with no decision transport.
     *
     * @property keepAbove The probability of `true` at or above which a cycle's lesson is
     *   stored. Strictly between 0 and 1, and **Ampere ships no default**: the number
     *   separating a lesson worth recall from routine is the consumer's (AMPR-422, Q5), and
     *   the judge exists so routine cycles do not fill recall with routine.
     */
    data class WhenLesson(val keepAbove: Double) : LearnCadence {

        init {
            require(keepAbove > 0.0 && keepAbove < 1.0) {
                "keepAbove is a probability strictly between 0 and 1; got $keepAbove"
            }
        }
    }

    /**
     * Let every successful cycle store its lesson, with no judge (AMPR-484, row N8).
     *
     * The same LEARN bracket and the same `cycle:<n>` rows as [WhenLesson] stores above its
     * threshold, after every executed cycle whose walk settled to a success; no decision call,
     * no judgment, no record beyond the phase's own and the `KnowledgeStored` each row leaves.
     * Needs no transport: `open` refuses nothing for it. The close-time LEARN is what it was.
     */
    data object EveryCycle : LearnCadence
}

/**
 * Whether a hosted run observes the Field, and how much of it reaches a prompt (AMPR-480,
 * rows N1 and N10 of the continuous-PROPEL epic).
 *
 * ## What it costs
 *
 * Stated here because the consumer is the one quoting for it:
 *
 * - [FieldScope.Goal] and [FieldScope.Full] are **store reads and no model call**: one
 *   `FieldService.snapshot` inside PERCEIVE, once per run, and one inside the OBSERVE bracket
 *   after each executed cycle, each as the host seat sees the Field. The PERCEIVE snapshot is
 *   rendered into the host seat's PERCEIVE prompt as a "# World" section; the after-cycle
 *   snapshot is taken and kept, and nothing is done with it yet (AMPR-479 folds it). The
 *   section makes the one PERCEIVE prompt longer by at most the bounds below; it adds no call.
 *   A run that executed nothing is not asked for the after-cycle snapshot, and neither is a
 *   cycle that held.
 * - [FieldScope.Off] reads nothing and renders nothing. It is the default, and the only
 *   setting under which a hosted run's prompts, event stream and `RunResult` are
 *   byte-identical to a run from before the Field was observed. A host built with no
 *   `FieldService` behaves as `Off` whatever this says.
 *
 * A snapshot that fails never fails the run: it is logged, naming the run and nothing it
 * holds, and that boundary behaves as `Off`.
 *
 * ## The sensitivity rule
 *
 * A `PROTECTED` belief or Observation is a belief about a real person (F12, AMPR-381 J11). The
 * PERCEIVE prompt goes to the host's generative model, which is off the device, so a
 * `PROTECTED` row is **counted and never rendered**: the section ends with
 * `(n protected entries not shown)` and carries nothing the row holds. There is no setting
 * that turns this off.
 *
 * @property field Which part of the Field the host seat's PERCEIVE is shown. See [FieldScope].
 * @property beliefLimit The most beliefs the snapshot carries and the most the section
 *   renders, newest first; Observations are bounded by the same number on their own. Zero
 *   renders no belief and no Observation.
 * @property commitmentLimit The most open commitments the section renders, soonest due first.
 * @property actionLimit The most taken Actions the section renders, newest first, counting
 *   only those no seat of this run took.
 * @property clip How many characters of any one entry's content reach the prompt. Longer text
 *   is cut there and an ellipsis appended, by the same convention the recalled section uses.
 */
@AmpereStableApi
data class ObservePolicy(
    val field: FieldScope = FieldScope.Off,
    val beliefLimit: Int = 20,
    val commitmentLimit: Int = 10,
    val actionLimit: Int = 20,
    val clip: Int = 200,
) {

    init {
        require(beliefLimit >= 0) { "the Field cannot contribute a negative number of beliefs; got $beliefLimit" }
        require(commitmentLimit >= 0) {
            "the Field cannot contribute a negative number of commitments; got $commitmentLimit"
        }
        require(actionLimit >= 0) { "the Field cannot contribute a negative number of actions; got $actionLimit" }
        require(clip > 0) { "a clipped entry has at least one character; got $clip" }
    }
}

/**
 * How much of the Field a hosted run's PERCEIVE is shown (AMPR-480, row N10).
 *
 * Every scope but [Off] is a store read and no model call; see [ObservePolicy] for the cost
 * and for the sensitivity rule every scope obeys.
 */
@AmpereStableApi
enum class FieldScope {

    /**
     * Read nothing, render nothing. The default, and the only setting under which a hosted
     * run's prompts, events and `RunResult` are what they were before AMPR-480, byte for byte.
     */
    Off,

    /**
     * The Field as it bears on this run's goal: the run's root goal and its state, every open
     * commitment, the beliefs about that goal or held by the host seat, the Observations the
     * host holds, and the taken Actions no seat of this run took — each branch under
     * [ObservePolicy]'s bounds. The default scope for a run that observes at all.
     */
    Goal,

    /**
     * Every branch of the snapshot under the same bounds and the same sensitivity rule, with
     * no goal or holder filter: every goal, every visible belief and Observation, every taken
     * Action by anyone outside this run.
     */
    Full,
}

/**
 * What RECALL reads, and how much of it the agent is told (AMPR-385 row H4b).
 *
 * The defaults are a run that recalls from the seat's own memory only and renders
 * what it found in a few hundred characters an item. Every number here is a parameter
 * rather than a constant because the right value depends on the model's context
 * budget and on how long the consumer's documents are, and neither is Ampere's to
 * guess.
 *
 * @property sources The consumer's own corpora, asked in order. Empty — the default —
 *   is a run that recalls from memory only. A source is asked once per run, during
 *   RECALL, and only when the host seat declares
 *   [HostedAgent.recallScopes][HostedAgent.recallScopes] to ask under.
 * @property perSourceLimit The most items any one source may contribute. Applied per
 *   source rather than across all of them, so adding a second corpus does not
 *   quietly halve the first one's share; a source that returns more than this has the
 *   first [perSourceLimit] of its own ordering taken.
 * @property memoryLimit The most entries the seat's own memory may contribute. The
 *   default matches what the PROPEL loop's own Recall asks for.
 * @property titleClip How many characters of an item's approach or title reach the
 *   prompt. Longer text is cut and an ellipsis appended.
 * @property textClip How many characters of an item's learnings or text reach the
 *   prompt, cut the same way. Larger than [titleClip] because this is the half that
 *   carries the content.
 * @property query What RECALL searches *for* (AMPR-457): the memory half's full-text
 *   description and every source's `intent`. Null — the default — searches with the
 *   goal's description, which is also what PLAN plans for; set it when the two differ,
 *   such as a run handed a photo, whose goal has to say something for PLAN but whose
 *   input has no words yet. Empty searches nothing: memory is recalled by [taskType]
 *   and the seat's scopes alone, and no source is asked, because a corpus query with
 *   no intent is a request for the whole corpus.
 * @property taskType Which bucket RECALL is scoped *to* (AMPR-457): the memory half's
 *   `MemoryContext.taskType`, which Recall matches exactly against the task type a row
 *   was stored under. Null — the default — keeps the goal's bucket (`CODE_CHANGE` for a
 *   code change, `GENERIC` otherwise). A run's own LEARN still files under the goal's
 *   bucket, so a consumer that sets this and wants to recall its own runs' lessons stores
 *   them under the same task type itself.
 */
@AmpereStableApi
data class RecallPolicy(
    val sources: List<RecallSource> = emptyList(),
    val perSourceLimit: Int = 3,
    val memoryLimit: Int = 10,
    val titleClip: Int = 200,
    val textClip: Int = 400,
    val query: String? = null,
    val taskType: String? = null,
) {

    init {
        require(perSourceLimit >= 0) { "a source cannot contribute a negative number of items; got $perSourceLimit" }
        require(memoryLimit >= 0) { "memory cannot contribute a negative number of entries; got $memoryLimit" }
        require(titleClip > 0) { "a clipped title has at least one character; got $titleClip" }
        require(textClip > 0) { "clipped text has at least one character; got $textClip" }
        require(taskType == null || taskType.isNotBlank()) {
            "a task type names a bucket; leave it null to keep the goal's"
        }
    }
}

/**
 * How LEARN closes the loop (AMPR-385 row H6, amended).
 *
 * Both write: one `recordOutcome` for the run, and one `Knowledge.FromOutcome`
 * through `AgentMemoryService.storeKnowledge` per seat that executed a step. They
 * differ only in whether a model is asked to reflect on the run as well.
 */
@AmpereStableApi
enum class LearnPolicy {

    /**
     * `KnowledgeExtractor.extractDefault` per seat, and no model call at all.
     *
     * The default, because LEARN is the phase a run cannot skip — a run that
     * produced outcomes and no Knowledge has not closed the loop — and a phase that
     * cannot be skipped must not be one that bills.
     */
    Deterministic,

    /**
     * Everything [Deterministic] writes, plus `OutcomeEvaluator` for the host seat.
     *
     * One extra model call per run, charged to the host seat and filed under LEARN.
     * Opt in when the distilled learning is worth paying for; the deterministic
     * extraction still happens either way, so this adds and never replaces.
     */
    ModelBacked,
}

/**
 * Who approves a plan before the run executes it (AMPR-385 row H1).
 *
 * The host seat plans and the consumer decides. [review] is suspending, so a gate
 * backed by a person — a sheet the user taps through, a review thread — holds the run
 * for as long as it takes without the run spinning or timing out; a gate is the one
 * place a hosted run is allowed to wait on something outside it.
 *
 * `review` is called once per cycle, after the plan's `TaskCreated`/`TaskStarted` are
 * on the record and before any step runs, so a stopped plan is still a plan the trace
 * holds.
 *
 * The gate sees each step's arguments. A step carries them as
 * [Task.Step.arguments][Task.Step.arguments], and [argumentsHash] is their hash over
 * canonical JSON, for a gate that keeps what it approved (AMPR-454, row H25). The plan the
 * gate approves is the plan that runs *with the arguments the gate approved*: a step
 * whose tool is about to receive different ones — because a parameter strategy filled in
 * what the planner left out, or a schema filled in a default — does not run until
 * [reviewChangedStep] has approved them.
 *
 * Under [RunPolicy.maxStepsPerCycle] the plan a gate is handed is already within the cap
 * and names only the run's tools, and a plan that had to be cut to get there has its
 * `PlanTruncated` on the record before `review` is called (AMPR-452).
 */
@AmpereStableApi
interface PlanGate {

    /** Approve [plan] — as written or trimmed — or stop the run. */
    suspend fun review(plan: Plan): PlanDecision

    /**
     * Approve [step] running with [actual] rather than [approved], skip it, or stop the run.
     *
     * Called during EXECUTE, after any parameter strategy has run and immediately before
     * the step's tool would be dispatched, when the arguments it would receive differ from
     * [approved] — the arguments [step] carried when [review] approved it — by
     * [StepArguments.same]. Called as well, failing closed, when the run cannot see the
     * arguments the tool reads: a tool whose hand-written strategy promotes its parameters
     * into a typed context is asked about on every dispatch. Never called for a step whose
     * arguments did not change, and called once per dispatch of the step's tool — which
     * `executePlanStep` makes once.
     *
     * [actual] is what the tool would receive: the arguments as validated against its
     * schema for a function tool, and the `tools/call` envelope for an MCP tool. It is
     * [kotlinx.serialization.json.JsonNull] when the tool would receive none.
     *
     * What the decision does:
     * - [PlanDecision.Approved] with a plan that still holds [step] — by id, carrying
     *   [actual] — runs it with [actual]. A step returned with any *other* arguments is not
     *   run and fails: a gate may approve or decline the call that is about to be made, and
     *   cannot substitute one nothing produced.
     * - [PlanDecision.Approved] with a plan that no longer holds [step] skips it, recorded
     *   as Skipped, and the run goes on to the next step.
     * - [PlanDecision.Stopped] ends the run `Stopped`, exactly as a stopped plan does: the
     *   step does not run, every later step of the plan is skipped, and no further cycle
     *   is planned.
     *
     * The default asks [review] about a one-step plan carrying [step] with [actual], so a
     * gate written before this method existed reviews the changed step as it would any
     * plan — and [Pass] approves it.
     */
    suspend fun reviewChangedStep(
        step: Task.Step,
        approved: JsonElement?,
        actual: JsonElement,
    ): PlanDecision {
        val changed = step.copy(arguments = actual)
        return review(Plan.ForTask(task = changed, tasks = listOf(changed), estimatedComplexity = 1))
    }

    /**
     * The gate that approves every plan unchanged.
     *
     * The default, and the only gate that adds nothing: a consumer with no review
     * step gets a run whose PLAN hands straight to EXECUTE.
     */
    object Pass : PlanGate {
        override suspend fun review(plan: Plan): PlanDecision = PlanDecision.Approved(plan)
    }
}

/** What a [PlanGate] decided about one plan. */
@AmpereStableApi
sealed interface PlanDecision {

    /**
     * Run [plan].
     *
     * It need not be the plan the gate was handed: a consumer may return one with
     * steps removed, which is the supported way to let a person drop a step they do
     * not want. Steps are never added — a gate that returns a plan with steps the
     * host did not produce is returning a plan nothing recorded the PLAN call for.
     */
    data class Approved(val plan: Plan) : PlanDecision

    /**
     * Run nothing more.
     *
     * No step of *this* plan executes — or, when the gate answers
     * [PlanGate.reviewChangedStep] with it, no step from the one whose arguments changed
     * onward, the steps before it having already run. The run still closes: LEARN records the
     * outcome, the task ends `TaskCompleted` — the run did what it was told, which
     * was to stop — and only the seats that executed something store Knowledge.
     * Stopping the first plan therefore stores none; stopping a later cycle's leaves
     * the earlier cycles' work exactly as it was.
     *
     * @property reason What to put on the record. Shown in the run's outcome, so
     *   write it for whoever reads the trace later.
     */
    data class Stopped(val reason: String = "stopped at the plan gate") : PlanDecision
}
