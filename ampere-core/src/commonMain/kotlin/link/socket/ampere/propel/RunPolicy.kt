package link.socket.ampere.propel

import link.socket.ampere.agents.domain.reasoning.Plan
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
 *   early when the planner returns no steps (AMPR-385 row H17). The cap is the
 *   consumer's, because the consumer is the one quoting for it.
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
 */
@AmpereStableApi
data class RunPolicy(
    val cycles: Int = 1,
    val learn: LearnPolicy = LearnPolicy.Deterministic,
    val planGate: PlanGate = PlanGate.Pass,
    val recall: RecallPolicy = RecallPolicy(),
    val goalCheck: GoalCheck = GoalCheck.Off,
    val judgmentResultClip: Int = 400,
) {

    init {
        require(cycles >= 1) { "a run runs at least one cycle; got $cycles" }
        require(judgmentResultClip > 0) {
            "a clipped result has at least one character; got $judgmentResultClip"
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
 */
@AmpereStableApi
data class RecallPolicy(
    val sources: List<RecallSource> = emptyList(),
    val perSourceLimit: Int = 3,
    val memoryLimit: Int = 10,
    val titleClip: Int = 200,
    val textClip: Int = 400,
) {

    init {
        require(perSourceLimit >= 0) { "a source cannot contribute a negative number of items; got $perSourceLimit" }
        require(memoryLimit >= 0) { "memory cannot contribute a negative number of entries; got $memoryLimit" }
        require(titleClip > 0) { "a clipped title has at least one character; got $titleClip" }
        require(textClip > 0) { "clipped text has at least one character; got $textClip" }
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
 * holds. H25 (re-gating a step whose arguments changed after approval) has no verdict
 * and is not implemented: the plan the gate approves is the plan that runs.
 */
@AmpereStableApi
interface PlanGate {

    /** Approve [plan] — as written or trimmed — or stop the run. */
    suspend fun review(plan: Plan): PlanDecision

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
     * No step of *this* plan executes, and the run still closes: LEARN records the
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
