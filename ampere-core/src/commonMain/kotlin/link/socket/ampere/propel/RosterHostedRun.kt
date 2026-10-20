package link.socket.ampere.propel

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.PlanExecutor
import link.socket.ampere.agents.domain.reasoning.PlanSeat
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.reasoning.defaultPerceptionContext
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.agents.domain.task.planStepSeat
import link.socket.ampere.agents.domain.task.planStepToolId
import link.socket.ampere.agents.events.api.TaskLifecycle
import link.socket.ampere.agents.events.api.openTaskLifecycle
import link.socket.ampere.agents.events.api.reattachTaskLifecycle
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.describeResult
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.domain.arc.GoalNode
import link.socket.ampere.domain.arc.GoalTree
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Field
import link.socket.ampere.field.FieldDelta
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.llm.DispatchingUpstreamLlmClient
import link.socket.ampere.llm.decide.Judgment
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.plug.permission.UserGrants
import link.socket.ampere.probe.ProbeSuite
import link.socket.ampere.probe.Verdict
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.SeatTool
import link.socket.ampere.util.logWith

/**
 * One run over one roster (AMPR-385 rows H1, H6).
 *
 * The host seat charges PERCEIVE → RECALL → OBSERVE → PLAN; [execute] runs the gated
 * plan a step at a time on the seat each step was assigned to, re-entering OBSERVE →
 * PLAN while cycles remain; [close] is LEARN.
 *
 * A step whose tool is waiting on a person ends the run there (AMPR-425): `PlanExecutor`
 * skips the rest of that cycle, and the run plans no further cycle, asks nothing about
 * the one that held, and learns nothing from it. Its task is left blocked, not ended.
 * With a [HeldRunStore] the run is recorded before [execute] returns, and a later
 * [resume] — on a new instance, possibly in a new process — rebuilds it from that record
 * and carries on with the approved plan (AMPR-431). Nothing of a held run stays alive in
 * memory between the two.
 *
 * A step that *fails* does not stop the walk (AMPR-450): every step that depends on it,
 * directly or through another, is skipped with the failed step's id as its reason, and
 * every other step still runs. A run where some steps succeeded and some failed ends
 * [RunStatus.Partial].
 *
 * Each step runs with the arguments the gate approved or not at all (AMPR-454, row H25):
 * a step whose tool is about to receive different ones — a parameter strategy filled them
 * in, a schema added a default — goes back to [PlanGate.reviewChangedStep] first, and a
 * stop there ends the run [RunStatus.Stopped] exactly as a stopped plan does.
 *
 * Under a step cap ([RunPolicy.maxStepsPerCycle], AMPR-452) every plan is bounded before
 * the gate sees it: one that is too long, or that names a tool no seat has, goes back to
 * the planner once, and a second plan that still breaks a rule is cut, with a
 * `PlanTruncated` on the record ahead of the gate ([withinBounds]).
 *
 * A cycle that executed something may also be *asked* about itself before the loop
 * decides whether to continue ([assess], AMPR-421). Every answer is recorded and none
 * is read: the loop, [currentStatus] and LEARN are the same with a judgment as without
 * one, which is the whole of what makes the question affordable to ask this early.
 * The same goes for the deterministic completion answer each executed cycle is given
 * by [GoalCompletion], the function the Arc's tick asks (AMPR-442).
 *
 * Every phase is bracketed on the host seat's door, and a step's work is bracketed
 * again on the seat that did it — so a run's EXECUTE is one span in the trace with the
 * seats' spans inside it. Every event carries [runId] on its envelope (F4, row H2),
 * which is the whole of what makes `ArcTraceProjection.project(runId)` the run.
 *
 * Nothing here blocks a thread and nothing here imposes a timeout: the phase services
 * are reached through each seat's suspending reasoning unit rather than through
 * `Agent`'s `runLLMTo*` lambdas, which wrap the same calls in `runBlockingCompat` and
 * a 60 s limit and therefore throw outright on JS and wasm. A run is driven by
 * whichever scope calls it, and cancelling that scope cancels the run.
 *
 * With a [GoalBranch] the run lives on the Field's Goal branch (AMPR-473): [charge] forms
 * one root goal — [ROOT_GOAL_NODE], held by the host seat — and registers every seat as a
 * Subject, and LEARN completes that goal or abandons it. The run plans steps, not sub-goals,
 * so the goal is never more than its root. Without one nothing touches the branch.
 *
 * With a [FieldService] and a [RunPolicy.observe] that asks for it, the run observes the Field
 * (AMPR-480, rows N1 and N10): one snapshot as the host seat inside PERCEIVE, rendered into that
 * seat's PERCEIVE prompt as the "# World" section, and one inside OBSERVE after each executed
 * cycle, kept on [observedField] for the fold the next ticket adds (AMPR-479) and read by nothing
 * here. Nothing is persisted and no event is added; a snapshot that fails is logged and that
 * boundary behaves as [FieldScope.Off]. Without a service, or with the scope `Off`, the run
 * touches no store and is what it was, byte for byte.
 *
 * Under a `Record` value of [RunPolicy.cadence] (AMPR-481, rows N6 and N7) the same OBSERVE
 * bracket asks the receptor's two questions — would a fresh perception read the world
 * differently, and did this cycle produce a lesson worth keeping — in one request of their own
 * over a [RunDrift]: the two snapshots folded ([FieldDelta.between]) and this cycle's results.
 * Recorded and never read, like the goal question; the loop, [currentStatus] and LEARN are
 * what they were.
 *
 * Under [PerceiveCadence.WhenDrifted] (AMPR-483, rows N3, N4, N5, N9 and N11) the drift
 * question is the [DriftReceptor]'s — a `Probe<RunDrift>` evaluated in that bracket inside a
 * `ProbeSuite` over the host's door, so its verdict is on the record beside the judgment — and
 * the loop reads the verdict, in the add-only direction: a `Violated` or an `Undetermined`
 * re-enters the next cycle at PERCEIVE instead of OBSERVE, a `Holds` leaves the run exactly as
 * it is today. [PerceiveCadence.EveryCycle] re-enters there without asking. A cycle that
 * re-entered at PERCEIVE runs RECALL again under [RecallCadence.WithPerceive], and its PLAN is
 * handed what moved as the "Observed since the last cycle" block — the first thing OBSERVE has
 * ever handed PLAN. Phase order is fixed: re-entry chooses where a cycle starts, never the order
 * it runs in. What PERCEIVE and RECALL last produced is held in a [PhaseCell] each, in memory.
 * No verdict ends a run, re-plans past the planner's empty answer or touches [currentStatus];
 * a run whose quota tripped does not re-perceive; a held, stopped, declined or cancelled run
 * never re-enters.
 *
 * Under [LearnCadence.WhenLesson] or [LearnCadence.EveryCycle] (AMPR-484, row N8) LEARN may
 * also *close a cycle*: after an executed cycle whose walk settled to a success, once the
 * receptor has been asked, the host seat opens one LEARN bracket and every seat that ran a step
 * in that cycle stores one deterministic `Knowledge.FromOutcome` through its own memory, tagged
 * `cycle:<n>` ([learnCycle]). Under `WhenLesson` the lesson judgment the receptor's request
 * came back with is the one thing read, against the consumer's `keepAbove`; a missing or
 * unmeasured one stores nothing. What a cycle stored is kept whatever the run's later end, and
 * the LEARN at [close] is untouched: one outcome row, Knowledge for a `Succeeded` run only.
 *
 * One run, one driver: the fields below are not synchronised, and two coroutines
 * driving the same run would interleave its phases.
 */
@OptIn(AmpereExperimentalApi::class)
internal class RosterHostedRun(
    override val runId: RunId,
    private val roster: Roster,
    private val seats: Map<RoleId, HostedSeat>,
    private val goal: Task,
    private val policy: RunPolicy,
    private val onClosed: (RosterHostedRun) -> Unit,
    private val heldRuns: HeldRunStore? = null,
    private val goalBranch: GoalBranch? = null,
    private val field: FieldService? = null,
) : HostedRun {

    /** The seat that plans. Validated to exist before the run was constructed. */
    private val host: HostedSeat = requireNotNull(seats[roster.host]) {
        "the roster's host '${roster.host.value}' has no seat"
    }

    /**
     * The seats as the planner is shown them, in the roster's order.
     *
     * A role nobody filled is left out rather than offered with no agent behind it:
     * the planner would assign steps to it and every one of them would fall back to
     * the host.
     *
     * Read on every PLAN, like [planTools], so the ids and the tool objects come off the
     * same live tool sets.
     */
    private val planSeats: List<PlanSeat>
        get() = roster.all().mapNotNull { seats[it.id]?.asPlanSeat() }

    /**
     * Every seat's tools, as the planner is shown them (AMPR-454).
     *
     * The host seat plans for the whole roster, so its planner is handed the tools of
     * every seat rather than the host's own: `PlanGenerator` reads a tool's description
     * and argument schema off the tool objects, and keeps a step's inline arguments only
     * for a tool among them. Handed the host's alone, every other seat's tool was listed
     * bare, offered no schema, and had its arguments dropped — so a gate reviewing a write
     * on another seat saw none of its fields. Each seat's set is already namespaced
     * (`<seat>/<tool>`) and narrowed by its spark stack, so the union holds no collisions
     * and nothing a seat could not run. Dispatch is unchanged: a step still runs only on
     * the seat that owns its tool.
     */
    private val planTools: Set<Tool<*>>
        get() = roster.all().flatMapTo(linkedSetOf()) { role -> seats[role.id]?.agent?.effectiveTools.orEmpty() }

    private var gatedPlan: Plan = Plan.blank
    private var stoppedAt: PlanDecision.Stopped? = null
    private var heldAt: StepOutcome.Held? = null

    /**
     * The cut that left a cycle's plan with no steps at all, or null (AMPR-452). Only an
     * unknown-tool drop can do that — the cap is at least 1 — and it ends the run as a
     * failure of the cycle: the planner asked for work this run could not attempt.
     */
    private var cutToNothing: PlanEvent.PlanTruncated? = null
    private var lifecycle: TaskLifecycle? = null
    private var ideas: List<Idea> = emptyList()
    private var recalled: List<KnowledgeWithScore> = emptyList()
    private val results = mutableListOf<StepOutcome>()

    /**
     * Every step of the run that failed or was skipped for a failure, mapped to the
     * failed step at the root of it (AMPR-450). A failed step maps to itself; its
     * dependents map to it, and so do theirs, which is what makes the skip transitive
     * without walking a graph. Step ids are unique across cycles (AMPR-424), so one map
     * serves the whole run.
     */
    private val failedRoots = mutableMapOf<TaskId, TaskId>()
    private val seatsThatRan = mutableSetOf<RoleId>()

    /**
     * The roles that executed a step in the latest cycle — this cycle's alone, where
     * [seatsThatRan] is every cycle's — for the LEARN that may close a cycle (AMPR-484).
     * Cleared by each [runCycle]. For the held cycle of a resumed run it holds the seats that
     * ran after the hold: the ones before it ran in another process, and are on the record
     * only run-wide.
     */
    private val cycleSeats = mutableSetOf<RoleId>()
    private var cyclesUsed: Int = 0
    private var lastPlanOutcome: Outcome = Outcome.blank
    private var cancellation: CancellationException? = null
    private var settled: RunResult? = null
    private var closed: Boolean = false
    private var shownExternal: Boolean = false

    /**
     * Where the first EXECUTE of a resumed run picks up the held cycle (AMPR-431): the
     * remaining steps' place in the approved plan, and the outcomes of the steps before
     * them, so the cycle folds to the outcome it would have had unbroken. Null for a run
     * that was opened, and consumed by the first cycle of one that was resumed.
     */
    private var resumePoint: ResumePoint? = null

    /**
     * The approved plan's steps up to and including the held one, while [plan] is the rest
     * of a resumed cycle (AMPR-431). Empty otherwise. A second hold in the same cycle is
     * recorded with these put back in front, so its resume indexes and folds against the
     * plan the person approved, not against the part of it left after the first hold.
     */
    private var heldCycleHead: List<Task> = emptyList()

    /** The reason a person declined the hold this run was resumed from, if they did. */
    private var declined: String? = null

    /**
     * Whether this run took up a held record, so closing it is what retires the record.
     * A run that never resumed leaves the store alone: a record under its id belongs to
     * an earlier run that used the same id.
     */
    private var ownsRecord: Boolean = false

    /**
     * What decides that the goal is complete. It is the same function, with the same
     * default, that `FlowPhase` asks after each agent's tick (AMPR-442, H19).
     */
    private val goalCompletion: GoalCompletion = GoalCompletion.AnySuccess
    private val completions = mutableListOf<Boolean>()

    /**
     * [goalCompletion]'s answer for each cycle that executed something, in cycle order
     * (AMPR-442).
     *
     * Recorded and never read. Nothing in the run consults it: not the loop, not
     * [currentStatus], not LEARN, and not [RunResult]. A run ending on its own judgment
     * of completion is behaviour H19 does not decide. It is not published either, so the
     * run's event stream is what it was. It exists so both drivers can be shown giving
     * the same answer to the same outcome, and so the seam AMPR-381 J9 replaces is on the
     * path a hosted run runs.
     */
    internal val goalCompletionAnswers: List<Boolean>
        get() = completions.toList()

    /**
     * Whether this run reads the Field at all (AMPR-480): a service to read it from, and a
     * policy that asks. Either missing is `Off`, and `Off` is byte-identical to before.
     */
    private val observesField: Boolean
        // `this.field`, because a bare `field` inside a getter is the backing-field keyword.
        get() = this.field != null && policy.observe.field != FieldScope.Off

    /**
     * The Field as the host seat saw it inside PERCEIVE, or null for a run that does not observe
     * or whose snapshot failed (AMPR-480, row N1). What the "# World" section was rendered from.
     */
    private var perceivedField: Field? = null

    /**
     * The Field as the host seat saw it inside the OBSERVE after the latest executed cycle, or
     * null (AMPR-480, row N1). The after-cycle request (AMPR-479, AMPR-481) folds it with
     * [perceivedField] into what moved, and under [PerceiveCadence.WhenDrifted] a `Holds` makes
     * it the next cycle's baseline (AMPR-483): what the receptor judged beside the goal is not
     * judged again.
     */
    private var observedField: Field? = null

    /** The two snapshots, for a test that has to see them; the loop reads them only to fold. */
    internal val fieldSnapshots: Pair<Field?, Field?>
        get() = perceivedField to observedField

    /**
     * What PERCEIVE last produced, from what, and in which cycle (AMPR-483, row N11), or null
     * before the first PERCEIVE and for a resumed run, whose ideas come off its held record.
     * In memory for the run's length; nothing persists or publishes it. [ideas] carries the
     * same value to PLAN and to the held record, as it did before the cell existed.
     */
    private var perceptionCell: PhaseCell<Idea>? = null

    /**
     * What RECALL last found, from what, and in which cycle (AMPR-483, row N11), or null before
     * the first RECALL. [recalled] carries its memory half to PLAN, as before.
     */
    private var recallCell: PhaseCell<RecalledContext>? = null

    /** The two cells, for a test that has to see them. */
    internal val phaseCells: Pair<PhaseCell<Idea>?, PhaseCell<RecalledContext>?>
        get() = perceptionCell to recallCell

    /**
     * Whether the next cycle re-enters at PERCEIVE rather than at OBSERVE (AMPR-483, row N5):
     * the drift receptor's verdict was not `Holds`, or the cadence re-perceives every cycle.
     * Set after an executed cycle, read and cleared by [reenter]. Never set for a cycle that
     * held, was stopped, was declined or was cancelled — those never reach the re-entry.
     */
    private var reentryAtPerceive: Boolean = false

    /**
     * What moved in the Field since the last PERCEIVE, rendered for the next PLAN (AMPR-483,
     * row N9), or null. Set beside [reentryAtPerceive] and handed to PLAN only on a cycle that
     * actually re-entered at PERCEIVE; dropped otherwise.
     */
    private var observedSinceLastCycle: String? = null

    /**
     * The outcomes of the steps the latest cycle executed — this cycle's alone, where [results]
     * is every cycle's — for the receptor's `cycle_results` (AMPR-481). Replaced by each
     * [runCycle]; empty before the first.
     */
    private var cycleResults: List<StepOutcome> = emptyList()

    /**
     * The answer to `lesson_worth_keeping` for the latest executed cycle, or null (AMPR-484).
     * Kept only under [LearnCadence.WhenLesson], the one value that reads it; under `Record`
     * the answer is dropped at the call site as every record-only answer is. Cleared by each
     * [runCycle], set by [askReceptor], read once by [learnCycle].
     */
    private var lessonJudgment: Judgment? = null

    /**
     * Whether the run has already said that drift could not be asked for lack of a snapshot
     * (AMPR-481). Logged once per run, not once per cycle: the cause — a Field that could not
     * be read — is the same every cycle, and the snapshot's own failure is already logged.
     */
    private var driftUnavailableLogged: Boolean = false

    override val plan: Plan
        get() = gatedPlan

    override val readExternalContent: Boolean
        get() = shownExternal

    /**
     * Notes that [shown] is about to be handed to a call that renders it into a prompt
     * (AMPR-453). Called before the call, so the flag is already up while it runs; it
     * never comes down again. RECALL raises it on its own, in [recall].
     */
    private fun aboutToShow(shown: List<StepOutcome>) {
        if (shown.any { it.origin == ContentOrigin.External }) shownExternal = true
    }

    // ========================================================================
    // Charge: PERCEIVE -> RECALL -> OBSERVE -> PLAN
    // ========================================================================

    /**
     * The four phases [RunHost.open] charges before it hands the run over.
     *
     * The goal is remembered first because that is how it reaches the prompts: the
     * Perceive phase renders the host seat's *current task* (AMPR-403), so a run whose
     * goal never reached the agent perceives nothing about it — which is the Arc path's
     * standing bug (AMPR-395), and not one to inherit.
     */
    suspend fun charge() {
        host.agent.rememberNewTask(goal)
        formGoal()
        perceive()
        recall()
        observe(cycle = 1)
        planCycle()
        cyclesUsed = 1
    }

    /**
     * Puts the run on the Goal branch, when it has one (AMPR-473): the run's one root goal,
     * held by the host seat — which `GoalBranch.form` registers — then every other seat, in
     * the roster's order, so a lesson any seat holds names a Subject the registry lists.
     *
     * A failure is logged and the run goes on without it: the branch is a record of the run,
     * not a precondition of it.
     */
    private suspend fun formGoal() {
        val branch = goalBranch ?: return
        onBranch("form its goal") {
            branch.form(GoalTree(root = GoalNode(id = ROOT_GOAL_NODE, description = goalDescription())), runId)
                .getOrThrow()
            roster.all().mapNotNull { seats[it.id] }
                .filter { it !== host }
                .forEach { seat -> branch.registerAgent(seat.id).getOrThrow() }
        }
    }

    /**
     * Closes the run's goal in LEARN (AMPR-473): completed when the run [met] it, abandoned on
     * any other end that is not a hold. A goal already closed is left as it is, with no event,
     * and a failure is logged rather than thrown.
     */
    private suspend fun closeGoal(met: Boolean) {
        val branch = goalBranch ?: return
        onBranch("close its goal") {
            if (met) {
                branch.complete(runId, ROOT_GOAL_NODE).getOrThrow()
            } else {
                branch.abandon(runId, ROOT_GOAL_NODE).getOrThrow()
            }
        }
    }

    /** Runs one branch write, logging rather than throwing what it fails with. */
    private suspend fun onBranch(what: String, write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWith("RosterHostedRun").w(e) { "Run $runId could not $what on the Goal branch" }
        }
    }

    /**
     * One read of the Field as the host seat sees it (AMPR-480), bounded by
     * [ObservePolicy.beliefLimit], or null when the read failed. A failure is logged on the
     * [onBranch] model — naming the run and the phase, never what the Field holds — and is never
     * thrown: the Field is a record the run looks at, not a precondition of it, and a run whose
     * world could not be read goes on exactly as one that was told not to look.
     */
    private suspend fun snapshotField(at: String): Field? {
        val service = field ?: return null
        return try {
            service.snapshot(viewer = Ref(host.id), beliefLimit = policy.observe.beliefLimit).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWith("RosterHostedRun").w(e) { "Run $runId could not read the Field at $at; it goes on as if Off" }
            null
        }
    }

    /** The run's root goal on the branch, or null for a run with no branch to have formed one on. */
    private fun rootGoalRef() = goalBranch?.let { GoalBranch.goalRef(runId, ROOT_GOAL_NODE) }

    /**
     * PERCEIVE, on the host seat. With a Field to observe (AMPR-480) the snapshot is taken first,
     * inside the bracket, and rendered into the seat's [HostedSeat.world] so the one PERCEIVE
     * call that follows is shown it; the holder is cleared again on the way out, so no later
     * prompt of the seat carries a world it perceived earlier. A section rendered is outside
     * content shown to a model, and raises [readExternalContent] as RECALL's does (AMPR-453).
     *
     * Called once by [charge], and again by [reenter] for a cycle that re-enters at PERCEIVE
     * (AMPR-483): the snapshot is taken at the top, so a re-perceive sees the world as it is
     * now, and the prior cell's [Idea] rides in `Perception.ideas` so the seat reads the world
     * against what it last concluded. What it produces replaces [perceptionCell].
     */
    private suspend fun perceive() {
        host.phases.withPhase(CognitivePhase.PERCEIVE, runId) {
            if (observesField) {
                perceivedField = snapshotField("perceive")
                val section = perceivedField?.asPerceptionSection(
                    scope = policy.observe.field,
                    rootGoal = rootGoalRef(),
                    holder = Ref(host.id),
                    ownActors = seats.values.mapTo(mutableSetOf()) { Ref<Subject>(it.id) },
                    policy = policy.observe,
                )
                if (section != null) shownExternal = true
                host.world.section = section?.let { "\n" + it + "\n" }
            }
            try {
                perceiveWithin()
            } finally {
                host.world.section = null
            }
        }
    }

    private suspend fun perceiveWithin() {
        // The stack is built and on the record before anything reasons with it: one
        // `SparkAppliedEvent` per spark as the seat was constructed, and this
        // snapshot of what they added up to.
        host.agent.emitCognitiveSnapshot()

        val state = host.agent.getCurrentState()
        // The cell's input is the context string the seat's builder renders for this call —
        // `defaultPerceptionContext` plus the "# World" section the holder carries right now —
        // digested, never kept: the world may hold a person's data (AMPR-483, row N11).
        val inputDigest = PhaseCell.digest(defaultPerceptionContext(state) + host.world.section.orEmpty())
        val perception = Perception(
            id = generateUUID("perception", host.id),
            currentState = state,
            // The prior cell's idea, or a resumed run's restored ideas: what the seat last
            // concluded, so a re-perceive reads the world against it (AMPR-483).
            ideas = ideas,
        )
        host.agent.rememberNewPerception(perception)
        val idea = host.agent.reasoningUnit.evaluatePerception(perception)
        host.agent.rememberNewIdea(idea)
        ideas = listOf(idea)
        perceptionCell = PhaseCell(value = idea, inputDigest = inputDigest, cycle = cyclesUsed + 1)
    }

    /**
     * RECALL: the host seat's own memory and the consumer's corpora, scoped to the goal
     * (rows H4a, H4b) unless [RecallPolicy.query] and [RecallPolicy.taskType] say
     * otherwise (AMPR-457). The goal is only the default: what PLAN plans for and what
     * RECALL searches for are separate inputs, and the event carries the context RECALL
     * actually ran with.
     *
     * Two reads, one event, two destinations. The memory half goes to PLAN as
     * `relevantKnowledge` (AMPR-388); both halves go to every seat's active prompt as
     * the "# Recalled context" section, so a step dispatched three phases later is
     * answered by an agent that still knows what the run recalled (AMPR-394).
     *
     * Each [link.socket.ampere.memory.RecallSource] is asked once, for at most
     * [RecallPolicy.perSourceLimit] items, and only when the host seat declared scopes
     * to ask under — an unscoped corpus query is a request for the whole corpus. The
     * sources are asked *before* the event is published, so the one
     * `KnowledgeRecalled` this phase leaves behind summarises what the whole phase
     * found rather than only the half Ampere stores.
     *
     * No model call: both halves are retrieval, and the clip lengths are what keeps
     * what they return affordable rather than a summarising call.
     *
     * Called once by [charge], by [resume] for a completed hold, and by [reenter] beside a
     * re-run PERCEIVE under [RecallCadence.WithPerceive] (AMPR-483, row N4) — the same query
     * and scopes each time, so what changes between two RECALLs of one run is what the stores
     * hold, not what was asked. What it finds replaces [recallCell].
     */
    private suspend fun recall() {
        host.phases.withPhase(CognitivePhase.RECALL, runId) {
            val recallPolicy = policy.recall
            val scopes = host.descriptor.recallScopes
            val context = host.agent.recallContextForRun(
                task = goal,
                scopes = scopes,
                query = recallPolicy.query,
                taskType = recallPolicy.taskType,
            )
            // AMPR-457: an explicitly empty query has no intent to hand a source, and a
            // source asked with none is asked for everything. A null query keeps the
            // goal's description and today's behaviour, whatever that description is.
            val searchesNothing = recallPolicy.query?.isEmpty() == true

            val found = RecalledContext(
                fromMemory = host.agent.recallForRun(context, recallPolicy.memoryLimit),
                fromSources = if (scopes.isEmpty() || searchesNothing) {
                    emptyList()
                } else {
                    recallPolicy.sources
                        .flatMap { source ->
                            source.recall(
                                intent = context.description,
                                scopes = scopes,
                                limit = recallPolicy.perSourceLimit,
                            ).take(recallPolicy.perSourceLimit)
                        }
                        .sortedByDescending { it.score }
                },
            )

            recalled = found.fromMemory
            recallCell = PhaseCell(
                value = found,
                // The context RECALL ran with, digested (AMPR-483, row N11): what it searched
                // for, under which scopes, in which bucket. The scopes are sorted so one set
                // digests one way whatever order the seat declared them in.
                inputDigest = PhaseCell.digest(
                    context.description,
                    scopes.sorted().joinToString(","),
                    context.taskType,
                ),
                cycle = cyclesUsed + 1,
            )
            // The publish result is deliberately not folded into the run, as in
            // `ProbeSuite`: the door logs a persist failure and reports it on the bus,
            // and a run that recalled something should go on to plan with it rather
            // than lose the phase to the event store.
            host.door.publish(
                found.asRecalledEvent(
                    agentId = host.id,
                    context = context,
                    runId = runId,
                    timestamp = Clock.System.now(),
                ),
                runId = runId,
            )
            val section = found.promptSection(recallPolicy.titleClip, recallPolicy.textClip)
            // AMPR-453: everything recalled is `External`, and the section is on every seat's
            // prompt from here on — so a run that recalled anything has shown a model outside
            // content by its first PLAN.
            if (section != null) shownExternal = true
            tellSeats(section)
        }
    }

    /**
     * Puts the recalled section on every seat's prompt, not only the host's.
     *
     * RECALL happens once, on the host seat, because a run is one cognition — so what
     * it found is the *run's* context, and the seat that executes a step three phases
     * later is exactly the one that would otherwise go looking for it again. A seat
     * that recalled nothing is told nothing: null leaves each prompt byte-for-byte what
     * it was.
     */
    private fun tellSeats(section: String?) {
        seats.values.forEach { seat -> seat.agent.recalledContext = section }
    }

    /**
     * OBSERVE: the verifier's probes, and what the transport can tell us about itself.
     *
     * Both are optional, and a roster with neither gets an empty bracketed phase
     * rather than no phase — a phase the run entered and found nothing in is a
     * different fact from a phase the run skipped, and only the first one is true here.
     */
    private suspend fun observe(cycle: Int) {
        host.phases.withPhase(CognitivePhase.OBSERVE, runId) {
            verifierSuite()?.evaluate(
                subjectId = runId,
                subject = RunObservation(
                    runId = runId,
                    goal = goal,
                    cycle = cycle,
                    priorResults = results.toList(),
                ),
            )

            // A transport that can answer locally is asked whether it can right now, so
            // the answer is part of the phase that observes rather than a surprise inside
            // the next model call. `AgentLLMService` reads the same probe when it routes.
            (host.descriptor.upstreamLlmClient as? DispatchingUpstreamLlmClient)
                ?.probeLocalCapacity()
        }
    }

    /**
     * The verifier seat's probes as a suite, or null when the roster names no verifier
     * or that seat declared none.
     *
     * Built here rather than handed in so the suite publishes through the verifier's
     * own door under this run: a `VerdictReached` nobody can attribute, on no run, is
     * a verdict the trace cannot show (F1, row H2).
     */
    private fun verifierSuite(): ProbeSuite<RunObservation>? {
        val verifier = roster.verifier?.let { seats[it] } ?: return null
        val probes = verifier.descriptor.probes.ifEmpty { return null }
        return ProbeSuite(
            probes = probes,
            eventApi = verifier.door,
            eventSource = EventSource.Agent(verifier.id),
            runId = runId,
        )
    }

    /**
     * PLAN, then the gate.
     *
     * The run's `TaskCreated` / `TaskStarted` go out here — once, on the first cycle —
     * because the plan is the first thing about the run there is anything to say about,
     * and they go out *before* the gate so a plan a person stopped is still a plan the
     * record holds.
     *
     * A later cycle plans with the results so far (AMPR-424, row H28): every step
     * outcome in [results] and the cycle number go to the planner, which renders them
     * as the "work already done" block and may answer with no steps. The first cycle
     * passes an empty list and `cycle = 1`, so its prompt and its step ids are exactly
     * what they were. A later plan with no steps is the planner saying the task is
     * complete, and it ends the run: there is nothing for the gate to approve or trim,
     * so it is not sent there, and [execute] sees an empty plan and stops. The status
     * is whatever the earlier steps made it — ending early is not a stop and not a
     * failure. Only a *later* cycle reads an empty plan that way: on the first, with
     * nothing in [results], an empty plan is what a missing transport leaves
     * (`Plan.blank`) and goes to the gate as before.
     *
     * Under a step cap the plan is bounded first ([withinBounds], AMPR-452): the gate
     * sees a plan within the cap that names only the run's tools, and a cut plan's
     * `PlanTruncated` goes out after the task lifecycle opens and before the gate. A plan
     * cut to nothing skips the gate, as an empty later plan does, but ends the run as a
     * failure rather than as the planner's "done".
     *
     * On a cycle that re-entered at PERCEIVE (AMPR-483, row N9) the planner is also handed
     * [observed] — what moved in the Field since the last PERCEIVE, rendered — which it puts
     * after the work already done as the "Observed since the last cycle" block. Null, which
     * is every other cycle, renders nothing. A re-plan under the cap is handed the same text.
     */
    private suspend fun planCycle(observed: String? = null): Unit =
        host.phases.withPhase(CognitivePhase.PLAN, runId) {
            aboutToShow(results)
            if (observed != null) shownExternal = true
            val cycle = cyclesUsed + 1
            val bounded = withinBounds(generatePlan(cycle, ideas, observed), cycle, observed)
            val generated = bounded.plan
            host.agent.rememberNewPlan(generated)
            heldCycleHead = emptyList()

            if (lifecycle == null) {
                lifecycle = host.door.openTaskLifecycle(
                    taskId = runId,
                    description = goalDescription(),
                    runId = runId,
                    assignedTo = host.id,
                    taskType = MemoryTaskTypes.GENERIC,
                )
            }

            bounded.truncation?.let { truncation ->
                host.door.publish(truncation, runId = runId)
                if (generated.tasks.isEmpty()) {
                    gatedPlan = generated
                    cutToNothing = truncation
                    return@withPhase
                }
            }

            if (generated.tasks.isEmpty() && results.isNotEmpty()) {
                gatedPlan = generated
                return@withPhase
            }

            when (val decision = policy.planGate.review(generated)) {
                is PlanDecision.Approved -> {
                    gatedPlan = decision.plan
                    stoppedAt = null
                }
                is PlanDecision.Stopped -> {
                    // The plan is dropped rather than kept-but-unrun: `plan` is what is
                    // about to happen, and after a stop that is nothing.
                    gatedPlan = Plan.blank
                    stoppedAt = decision
                }
            }
        }

    private suspend fun generatePlan(cycle: Int, ideas: List<Idea>, observed: String?): Plan =
        host.agent.reasoningUnit.generatePlan(
            task = goal,
            ideas = ideas,
            relevantKnowledge = recalled,
            seats = planSeats,
            priorResults = results.toList(),
            cycle = cycle,
            availableTools = planTools,
            observed = observed,
        )

    /**
     * [generated], held to [RunPolicy.maxStepsPerCycle] and to the run's tools (AMPR-452,
     * row H20 (c)).
     *
     * With no cap this is [generated] untouched: no check, no re-plan, no event, so a run
     * without a cap is what it was before the cap existed. With one, a plan that is
     * longer than the cap or names a tool no seat owns goes back to the planner
     * **once**, with what was wrong added to its ideas — rendered with the perception
     * insights, because that is the slot `PlanGenerator` gives a caller to tell it
     * something without a second prompt. That is a second PLAN call on the host seat,
     * and it bills.
     *
     * A second plan that still breaks a rule is cut, in a fixed order: drop every step
     * naming an unknown tool, then keep the first `cap` of what is left. The cut is the
     * returned [BoundedPlan.truncation], published by [planCycle] before the gate. A
     * kept step may depend on a dropped one; [failedDependencyOf] reads a dependency
     * that is not in the run as no reason to skip, as it does one the gate trimmed.
     *
     * "Unknown" is the dispatch rule applied early: a tool id no seat of this run owns
     * would fail `executePlanStep` as "not in the agent's effective tool set". A step
     * naming no tool is a reasoning step and is never unknown.
     */
    private suspend fun withinBounds(generated: Plan, cycle: Int, observed: String?): BoundedPlan {
        val cap = policy.maxStepsPerCycle ?: return BoundedPlan(generated)
        val first = boundsBrokenBy(generated, cap) ?: return BoundedPlan(generated)

        val replanned = generatePlan(cycle, ideas + first.asCorrection(), observed)
        val second = boundsBrokenBy(replanned, cap) ?: return BoundedPlan(replanned)

        val known = replanned.tasks.filter(::namesKnownTool)
        val kept = known.take(cap)
        val cut = replanned.withTasks(kept)
        val reasons = buildList {
            val dropped = replanned.tasks.size - known.size
            if (dropped > 0) {
                add(
                    "dropped $dropped step(s) naming tools this run does not have: " +
                        second.unknownToolIds.joinToString(", "),
                )
            }
            if (known.size > cap) add("cut ${known.size - cap} step(s) over the cap of $cap per cycle")
        }
        return BoundedPlan(
            plan = cut,
            truncation = PlanEvent.PlanTruncated(
                eventId = generateUUID(runId, "plan-truncated"),
                runId = runId,
                planId = cut.id,
                cycle = cycle,
                originalStepCount = replanned.tasks.size,
                keptStepCount = kept.size,
                reasons = reasons,
                maxStepsPerCycle = cap,
                droppedToolIds = second.unknownToolIds,
                eventSource = EventSource.Agent(host.id),
                timestamp = Clock.System.now(),
            ),
        )
    }

    /** The rules [plan] breaks under [cap], or null when it breaks none. */
    private fun boundsBrokenBy(plan: Plan, cap: Int): BrokenBounds? {
        val unknown = plan.tasks
            .filterNot(::namesKnownTool)
            .mapNotNull { it.planStepToolId }
            .distinct()
        return BrokenBounds(stepCount = plan.tasks.size, cap = cap, unknownToolIds = unknown)
            .takeIf { it.overCap || unknown.isNotEmpty() }
    }

    /** Whether some seat of this run can dispatch the tool [step] names; true for no tool. */
    private fun namesKnownTool(step: Task): Boolean =
        step.planStepToolId?.let { toolId -> seats.values.any { it.owns(toolId) } } ?: true

    // ========================================================================
    // EXECUTE, and the cycles
    // ========================================================================

    override suspend fun execute(): RunResult {
        settled?.let { return it }

        while (true) {
            // A resumed run's first cycle is the held one, finishing: it runs even when the
            // held step was the plan's last, so the cycle is folded and asked about, and it
            // is not read as a planner with nothing left to ask for.
            val finishingHeldCycle = resumePoint != null
            if (stoppedAt == null && (gatedPlan.tasks.isNotEmpty() || finishingHeldCycle)) {
                runCycle(gatedPlan)
                // The Arc's completion question, asked of this cycle's outcome and
                // recorded. Nothing below reads the answer.
                completions += goalCompletion.isComplete(lastPlanOutcome)
                // A held cycle is not asked whether the goal is met: it is waiting, and
                // the run has already decided that nothing follows it. Nor is a cycle the
                // gate stopped part-way, once a step's arguments changed (AMPR-454): the
                // gate has said nothing more runs.
                if (heldAt == null && stoppedAt == null) {
                    assess()
                    // No judge under `EveryCycle`: every executed cycle re-enters at PERCEIVE
                    // (AMPR-483, row N5). Decided here rather than in `assess`, which a run
                    // that observes nothing and asks nothing returns from before its bracket.
                    if (policy.cadence.perceive == PerceiveCadence.EveryCycle) reentryAtPerceive = true
                    // LEARN may close the cycle (AMPR-484, row N8): after the receptor has
                    // been asked, before the loop decides whether to continue.
                    learnCycle()
                }
            }
            captureCancellation()
            // A hold is a full stop, whatever `cycles` says: the next PLAN would be planning
            // past a step whose output does not exist yet (AMPR-425).
            // A declined hold ends the run as it stands (AMPR-431): nothing after the held
            // step runs, and no further cycle is planned whatever the cap.
            if (cancellation != null || stoppedAt != null || heldAt != null || declined != null) break
            // The cap and the planner are the two ways a run ends: whichever comes
            // first. An empty plan means the host had nothing left to ask for.
            if (cyclesUsed >= policy.cycles || (gatedPlan.tasks.isEmpty() && !finishingHeldCycle)) break

            // Where the next cycle starts (AMPR-483, row N5): at PERCEIVE when the cycle
            // asked for it, else at OBSERVE as every cycle did before. Never reordered.
            val observed = reenter()
            observe(cycle = cyclesUsed + 1)
            planCycle(observed)
            cyclesUsed++
        }

        val status = currentStatus()
        val result = RunResult(
            runId = runId,
            status = status,
            cyclesUsed = cyclesUsed,
            stepOutcomes = results.toList(),
            outcome = executionOutcome(cancelled = cancellation != null),
            readExternalContent = shownExternal,
            resumable = status == RunStatus.Held && record(),
        )
        settled = result
        cancellation?.let { throw it }
        return result
    }

    /**
     * The re-entry into PERCEIVE a cycle may ask for (AMPR-483, rows N4, N5 and N9), taken
     * before the OBSERVE every later cycle re-enters at. Answers what the cycle's PLAN is to
     * be shown about the world, or null.
     *
     * When [reentryAtPerceive] is up — the drift receptor convicted or could not decide, or
     * the cadence re-perceives every cycle — PERCEIVE runs again on the host seat, shown the
     * world as it is now, then RECALL under [RecallCadence.WithPerceive], and the delta the
     * receptor was asked about is what the cycle's PLAN is handed. Otherwise nothing runs
     * here and the cycle goes on exactly as it did before this seam existed.
     *
     * A run whose quota has tripped does not re-perceive. A quota trips steps, not the loop:
     * the next PLAN still runs and its first step still fails on the quota, as it did before
     * (AMPR-445); what this keeps out of a tripped run is one more PERCEIVE model call and
     * one more store read on the way to that failure. The PLAN half — whether a tripped run
     * should plan at all — is its own ticket. `quotaReached` reads the record only when a
     * re-perceive was asked for, so a run under the default cadence reads nothing here.
     *
     * Both flags are consumed whatever happens, so a re-perceive refused by the quota does
     * not carry over to the cycle after.
     */
    private suspend fun reenter(): String? {
        val atPerceive = reentryAtPerceive
        val observed = observedSinceLastCycle
        reentryAtPerceive = false
        observedSinceLastCycle = null
        if (!atPerceive) return null
        if (quotaReached()) {
            logWith("RosterHostedRun").i {
                "Run $runId was to re-enter cycle ${cyclesUsed + 1} at PERCEIVE and a quota has tripped; " +
                    "it re-enters at OBSERVE instead"
            }
            return null
        }

        perceive()
        if (policy.cadence.recall == RecallCadence.WithPerceive) recall()
        return observed
    }

    /**
     * One EXECUTE: every step of [plan], each on the seat it was assigned to.
     *
     * [PlanExecutor] does the walking — the sequential order, the running chain of
     * results, the stop on a critical failure or a hold and the skipping of what came
     * after it, and the `PlanStepStarted` / `PlanStepCompleted` pair per step (row H5).
     * It is handed a door per step rather than one door, so each pair leaves through the
     * seat that ran the step; the run-level door is the fallback for a step no seat
     * claimed. A step skipped because a step it depends on failed ran on no seat, so its
     * pair leaves through the host's door: the skip is the run's decision, not the
     * assigned seat's work.
     *
     * A failed step does not stop the walk here (AMPR-450). [dispatch] hands
     * `PlanExecutor` the failure as non-critical, and skips each later step that
     * depends on it — so a failure costs exactly the steps that needed its output.
     *
     * Dispatch itself goes through [dispatchPlanStep], the same function the Arc path's
     * Execute reaches through `SparkBasedAgent.executePlanStep` (AMPR-442). That is what
     * keeps one definition of what executing a step means: the Breaker's admission
     * (AMPR-440), strict tool-id routing against the seat's spark-narrowed tool set
     * (AMPR-400), a tool-less step carried out as that seat's own model call
     * (AMPR-407), the earlier steps' results on the request (AMPR-408, AMPR-412), and
     * a step's inline arguments settling the call without a parameter model call
     * (AMPR-410, AMPR-411). None of it is re-implemented here.
     *
     * A held step is noticed here, not decided here: `PlanExecutor` has already stopped
     * the walk on it and skipped the rest (AMPR-425). What the run adds is the record —
     * its task is marked blocked on the held step, which is the state a resumed run would
     * pick up — and [execute]'s decision not to plan again.
     *
     * A cycle the gate stopped part-way (AMPR-454) leaves [plan] [Plan.blank], as a
     * stopped plan does: nothing more is about to run.
     */
    private suspend fun runCycle(toRun: Plan) {
        // The held cycle of a resumed run is walked from the step after the held one, with
        // the approved plan's indices, and folded with the steps that ran before the hold.
        val resuming = resumePoint
        resumePoint = null
        cycleSeats.clear()
        lessonJudgment = null
        val execution = host.phases.withPhase(CognitivePhase.EXECUTE, runId) {
            // The summary is the walk's (PlanExecutor's default), not the host seat's: the
            // seat is the door's identity on every pair, not an executor (AMPR-443).
            stepWalker().execute(
                plan = toRun,
                priorResults = results.toList(),
                firstStepIndex = resuming?.firstStepIndex ?: 0,
                totalSteps = resuming?.totalSteps ?: toRun.tasks.size,
                earlierStepOutcomes = resuming?.earlierStepOutcomes.orEmpty(),
            ) { step, context ->
                dispatch(step, context.priorResults)
            }
        }

        results += execution.stepOutcomes
        cycleResults = execution.stepOutcomes
        lastPlanOutcome = execution.outcome

        execution.stepOutcomes.firstNotNullOfOrNull { it as? StepOutcome.Held }?.let { held ->
            heldAt = held
            lifecycle?.blocked(blockedByTaskId = held.id, reason = held.reason)
        }
        if (stoppedAt != null) gatedPlan = Plan.blank
    }

    /**
     * The walk a cycle's steps take: each pair through the door of the seat that ran it,
     * and through the host's for a step skipped because one it depends on failed.
     */
    private fun stepWalker(): PlanExecutor = PlanExecutor(
        eventApi = host.door,
        runId = runId,
        stepEventApi = { step ->
            if (failedDependencyOf(step) != null) host.door else seatFor(step).door
        },
    )

    /**
     * The questions a cycle that executed is asked, recorded and never read (AMPR-421).
     *
     * Two of them, both optional and independently opted into: the run's own "is the
     * goal met?" on the host seat when [RunPolicy.goalCheck] is [GoalCheck.Record], and
     * whatever the roster's verifier seat declared on [HostedAgent.questions]. A roster
     * that opted into neither returns before the phase is even entered, which is what
     * makes the default run's event stream what it was, event for event.
     *
     * Here — after EXECUTE, before the loop decides whether to continue — rather than
     * folded into the next cycle's OBSERVE, and that placement is the point of the
     * ticket rather than a detail of it. This is where a judgment that *acts* would
     * sit: the last cycle's results are in hand and nothing has been committed to about
     * what happens next. An OBSERVE one cycle later cannot be that seam, because by
     * then the run has already decided to continue, and the final cycle never reaches
     * one at all. Nothing acts yet (AMPR-381 rows J6, J8, J9 have no verdict); the
     * records are what AMPR-422 measures before one is taken.
     *
     * Bracketed as OBSERVE on the host seat even when the only question asked is the
     * verifier's, because the phase is the run's and the trace files a judgment under
     * the phase that asked — `JudgmentRecorded.cognitivePhase` is what
     * `ArcTraceProjection.phaseNameFor` reads. The state is rendered once and both calls
     * are given the same string, so the two records share a `stateDigest` and a reader
     * can tell they are about one moment.
     *
     * Nothing is asked after a cancelled cycle. The [cancellation] field covers a
     * cancellation `dispatch` caught; the live check covers the common case it cannot —
     * a tool's cancellation is swallowed into a failed step by
     * `FunctionExecutor`, so the walk returns normally on a dead scope. Without the
     * check a `DeterministicDecisionClient`, which has no suspension point to be
     * cancelled at, would answer and the record would go out under `NonCancellable`.
     *
     * A run that observes the Field (AMPR-480, row N1) enters the same bracket whether or not
     * anything is asked, and reads the world first — "entered and read the world" is a phase
     * even with no question in it, and one bracket, not two, when there is one. The snapshot is
     * kept on [observedField], and the receptor is the one thing that reads it.
     *
     * The receptor's two questions (AMPR-481, rows N6 and N7) are asked last, in a request of
     * their own over their own state — a [RunDrift] — so the goal question's request, state
     * and digests are untouched: `perception_drift` under [PerceiveCadence.Record] and
     * `lesson_worth_keeping` under [LearnCadence.Record], both on the host seat, keyed by
     * their ids, and the two records of one cycle share one `stateDigest`. The drift question
     * needs the two snapshots; when the run has only one or none — a snapshot failed — it is
     * left out, the lesson question goes alone over an empty `changes`, and the run logs once.
     * [recordOnly] drops the answers as it drops the goal question's.
     *
     * Under [PerceiveCadence.WhenDrifted] (AMPR-483) the drift question is the
     * [DriftReceptor]'s, and the one answer the loop reads: see [askReceptor]. Under
     * [PerceiveCadence.EveryCycle] nothing is asked, but a run that observes still folds the
     * two snapshots here so the re-perceiving cycle's PLAN can be shown what moved.
     */
    private suspend fun assess() {
        if (cancellation != null || !coroutineContext.isActive) return

        val asksGoal = policy.goalCheck == GoalCheck.Record
        val verifier = roster.verifier
            ?.let { seats[it] }
            ?.takeIf { it.descriptor.questions.isNotEmpty() }
        val observes = observesField
        val perceiveCadence = policy.cadence.perceive
        val asksReceptor = perceiveCadence == PerceiveCadence.Record ||
            perceiveCadence is PerceiveCadence.WhenDrifted ||
            asksLesson
        if (!asksGoal && verifier == null && !observes && !asksReceptor) return

        host.phases.withPhase(CognitivePhase.OBSERVE, runId) {
            if (observes) observedField = snapshotField("observe")
            if (asksGoal || verifier != null) {
                val state = RunObservation(
                    runId = runId,
                    goal = goal,
                    cycle = cyclesUsed,
                    priorResults = results.toList(),
                ).asDecisionState(policy.judgmentResultClip)
                aboutToShow(results)

                if (asksGoal) {
                    recordOnly(host) {
                        host.agent.reasoningUnit.decide(
                            state = state,
                            questions = mapOf(RunQuestions.GOAL_MET_ID to RunQuestions.goalMet),
                            phase = CognitivePhase.OBSERVE,
                        )
                    }
                }
                verifier?.let { seat ->
                    recordOnly(seat) {
                        seat.agent.reasoningUnit.decide(
                            state = state,
                            questions = seat.descriptor.questions,
                            phase = CognitivePhase.OBSERVE,
                        )
                    }
                }
            }
            if (asksReceptor) {
                askReceptor()
            } else if (perceiveCadence == PerceiveCadence.EveryCycle) {
                // Nothing to ask, but the re-perceiving cycle's PLAN is shown what moved
                // (AMPR-483, row N9) when there are two snapshots to fold.
                val (delta, names) = foldDrift()
                observedSinceLastCycle = delta?.asObservedText(policy.observe, names)
            }
        }
    }

    /**
     * The receptor's request (AMPR-481, AMPR-483): one `decide` on the host seat over
     * [RunDrift], carrying whichever of the two questions the cadence asks, inside the
     * OBSERVE bracket [assess] opened.
     *
     * Called after the goal question so the order of a cycle's records is the goal's, then the
     * receptor's; the two are separate requests over separate states either way.
     *
     * Under a `Record` cadence nothing reads what comes back. Under
     * [PerceiveCadence.WhenDrifted] the request is the [DriftReceptor]'s — evaluated in a
     * `ProbeSuite` over the host's door under this run, so its verdict leaves as a
     * `ProbeEvent.VerdictReached` beside the `JudgmentRecorded` the call leaves — and the
     * lesson question rides in the same request when [LearnCadence.Record] asks for it, so the
     * cycle's two records still share one state and one digest. The verdict is the one thing
     * the loop reads: anything but `Holds` re-enters the next cycle at PERCEIVE, and the
     * rendered delta is kept for that cycle's PLAN. A `Holds` also makes the after-cycle
     * snapshot the next cycle's baseline, so what was judged beside the goal is not judged
     * again. With no two snapshots to fold — the Field could not be read — there is nothing
     * for the receptor to evaluate: the run logs once, treats the cycle as unchecked and
     * re-enters at PERCEIVE (convict-but-not-acquit), and the lesson question, if on, goes
     * alone over an empty `changes` as it does under `Record`.
     */
    private suspend fun askReceptor() {
        val (delta, names) = foldDrift()
        val perceiveCadence = policy.cadence.perceive
        val lesson = if (asksLesson) {
            mapOf(RunQuestions.LESSON_WORTH_KEEPING_ID to RunQuestions.lessonWorthKeeping)
        } else {
            emptyMap()
        }
        val asksDrift = perceiveCadence == PerceiveCadence.Record || perceiveCadence is PerceiveCadence.WhenDrifted
        if (asksDrift && delta == null && !driftUnavailableLogged) {
            driftUnavailableLogged = true
            logWith("RosterHostedRun").w {
                "Run $runId has no Field snapshot to fold, so perception_drift cannot be asked; " +
                    "the Field could not be read at PERCEIVE or after a cycle" +
                    if (perceiveCadence is PerceiveCadence.WhenDrifted) ", and an unchecked cycle re-perceives" else ""
            }
        }

        if (perceiveCadence is PerceiveCadence.WhenDrifted && delta != null) {
            val receptor = DriftReceptor(
                reasoning = host.agent.reasoningUnit,
                moveAbove = perceiveCadence.moveAbove,
                clip = policy.judgmentResultClip,
                alsoAsk = lesson,
            )
            aboutToShow(cycleResults)
            val verdict = evaluateReceptor(receptor, runDrift(delta, names)) ?: return
            keepLesson(receptor.judgments)
            if (verdict is Verdict.Holds) {
                perceivedField = observedField
            } else {
                reentryAtPerceive = true
                observedSinceLastCycle = delta.asObservedText(policy.observe, names)
            }
            return
        }
        if (perceiveCadence is PerceiveCadence.WhenDrifted) reentryAtPerceive = true

        val questions = buildMap<String, Question> {
            if (perceiveCadence == PerceiveCadence.Record && delta != null) {
                put(RunQuestions.PERCEPTION_DRIFT_ID, RunQuestions.perceptionDrift)
            }
            putAll(lesson)
        }
        if (questions.isEmpty()) return

        val drift = runDrift(delta ?: noDrift(), names)
        aboutToShow(cycleResults)
        recordOnly(host) {
            val response = host.agent.reasoningUnit.decide(
                state = drift.asDecisionState(policy.judgmentResultClip),
                questions = questions,
                phase = CognitivePhase.OBSERVE,
            )
            keepLesson(response.judgments)
        }
    }

    /** Whether the receptor's request carries [RunQuestions.lessonWorthKeeping] this run. */
    private val asksLesson: Boolean
        get() = policy.cadence.learn == LearnCadence.Record || policy.cadence.learn is LearnCadence.WhenLesson

    /**
     * Keeps the lesson answer out of [judgments] for [learnCycle] to read — under
     * [LearnCadence.WhenLesson] only. Under `Record` the answer is discarded here with the
     * rest, so "nothing reads the answer" stays visible at the call site (AMPR-481).
     */
    private fun keepLesson(judgments: Map<String, Judgment>) {
        if (policy.cadence.learn is LearnCadence.WhenLesson) {
            lessonJudgment = judgments[RunQuestions.LESSON_WORTH_KEEPING_ID]
        }
    }

    /**
     * The two snapshots folded into what moved (AMPR-479), with the names the fold's
     * references resolve to — the later snapshot's over the earlier one's for anything it no
     * longer holds — or a null delta when the run has fewer than two snapshots to fold.
     */
    private fun foldDrift(): Pair<FieldDelta?, Map<String, String>> {
        val before = perceivedField
        val after = observedField
        val delta = if (before != null && after != null) {
            val ownActors = seats.values.mapTo(mutableSetOf()) { Ref<Subject>(it.id) }
            FieldDelta.between(before, after, ownActors = ownActors)
        } else {
            null
        }
        val names = buildMap {
            listOfNotNull(before, after).forEach { field ->
                field.subjects.forEach { put(it.id.value, it.name) }
                field.goals.forEach { put(it.goal.id.value, it.goal.description) }
            }
        }
        return delta to names
    }

    /** The receptor's state for the cycle that just executed (AMPR-481, row N6). */
    private fun runDrift(delta: FieldDelta, names: Map<String, String>): RunDrift = RunDrift(
        runId = runId,
        goal = goal,
        cycle = cyclesUsed,
        perceived = ideas.firstOrNull()?.description.orEmpty(),
        delta = delta,
        cycleResults = cycleResults,
        names = names,
    )

    /**
     * Runs [receptor] over [drift] inside a `ProbeSuite` on the host's door under this run
     * (AMPR-483), the way the verifier's probes run, so the verdict is attributed to the seat
     * that reached it and `ArcTraceProjection.project(runId)` can find it. Answers the verdict,
     * or null when the scope was cancelled while the receptor ran — held in [cancellation] as
     * [recordOnly] holds one, so [execute] rethrows it once the loop has unwound. A transport
     * that throws is not a throw here: the receptor reads it as `Undetermined`.
     */
    private suspend fun evaluateReceptor(receptor: DriftReceptor, drift: RunDrift): Verdict? = try {
        ProbeSuite(
            probes = listOf(receptor),
            eventApi = host.door,
            eventSource = EventSource.Agent(host.id),
            runId = runId,
        ).evaluate(subjectId = runId, subject = drift).single().verdict
    } catch (e: CancellationException) {
        cancellation = e
        null
    }

    /**
     * Asks, and drops whatever comes back.
     *
     * The judgments are discarded at the call site rather than stored unread, so that
     * "nothing reads the answer" is visible here rather than asserted in a comment.
     *
     * A transport that throws costs the run nothing: `AgentReasoning.decide` records
     * nothing when the call fails, and a question the consumer asked for its own
     * records is not a reason to fail a run whose work is already done. A
     * [CancellationException] is held in [cancellation] exactly as [dispatch] holds
     * one, so [execute] rethrows it after the loop unwinds rather than losing it to
     * this `catch`.
     */
    private suspend fun recordOnly(seat: HostedSeat, ask: suspend () -> Unit) {
        try {
            ask()
        } catch (e: CancellationException) {
            cancellation = e
        } catch (throwable: Throwable) {
            logWith("RosterHostedRun").w(throwable) {
                "A decision call for seat '${seat.role.value}' of run $runId failed; " +
                    "no judgment was recorded and the run is unaffected"
            }
        }
    }

    /**
     * Notices that the scope driving the run has been cancelled.
     *
     * [dispatch]'s own `catch` covers a cancellation that propagates out of a step —
     * a reasoning step's model call, say. It does *not* cover the common case: a tool's
     * cancellation never reaches here at all, because `FunctionExecutor` and
     * `ToolExecutionEngine` both `catch (e: Exception)` and turn what they caught into a
     * failed outcome, and `CancellationException` is an `Exception`. So the step comes
     * back as a failure, the walk finishes normally, and without this the run would
     * report a failed step on a live run rather than a cancelled one.
     *
     * Checked after the plan walk rather than during it, so the pairs `PlanExecutor`
     * publishes under `NonCancellable` are on the record before [execute] rethrows.
     */
    private suspend fun captureCancellation() {
        if (cancellation != null) return
        try {
            coroutineContext.ensureActive()
        } catch (e: CancellationException) {
            cancellation = e
        }
    }

    /**
     * The rules this run puts on every step, whichever seat runs it: the policy's quotas,
     * each a Hard `Rule.Quota` (AMPR-445). The seat's Breaker reads the run's spend from the
     * events filed under [runId] — every seat's door writes to the one store — so a quota
     * counts what all the seats spent, not only the seat that is about to spend more.
     */
    private val runRules = policy.quotas.asRules()

    /**
     * The same quotas, asked at the run's level when a step fails, so [dispatch] can tell a
     * reached quota from one step's fault (AMPR-445). Null when the run set no quota, which
     * is the default and reads nothing.
     */
    private val quotaCheck: Breaker? = if (runRules.isEmpty()) {
        null
    } else {
        Breaker(userGrants = { UserGrants() }, eventApi = host.door, actorId = host.id, runId = runId)
    }

    /**
     * Runs [step] on its seat, or skips it because the run was stopped or a step it
     * depends on failed.
     *
     * A failure that comes back is handed on as *non-critical*, so `PlanExecutor` walks
     * on to the next step instead of skipping every one after it (AMPR-450). Every
     * failure `executePlanStep` reports is critical, which before row H20's ruling meant
     * one failed step ended the cycle; what decides what a failure costs is now
     * [Task.Step.dependsOn], read by [failedDependencyOf]. A step that throws is a
     * failed step like any other, for the same reason.
     *
     * Three failures stay critical, because they are not one step's fault: a cancellation
     * caught here; any failure on a scope that is no longer active — which is how a
     * tool's own cancellation arrives, swallowed into a failed outcome by
     * `FunctionExecutor` (see [captureCancellation]); and any failure once a run quota is
     * reached (AMPR-445). Walking on from the first two would dispatch the next step on a
     * dead run; walking on from the third would trip every remaining step one at a time,
     * because a spend never goes back down.
     *
     * The step runs under the check that holds it to the arguments the gate approved
     * (AMPR-454, row H25). The check rides in the coroutine context into the seat's
     * `ToolExecutionEngine`, which consults it at its dispatch funnel — the one point
     * every tool call passes after its parameters are final and before the tool runs.
     * Nothing below this function needs to know about it, and no path outside a hosted
     * run carries one. What the gate said when it was asked again is read back here: a
     * step it removed is recorded Skipped rather than as the failure the engine returned
     * for it, and — like a step the [PlanGate] trimmed — is no reason to skip its
     * dependents; a step it stopped is recorded Skipped too, the run is marked stopped,
     * and every later step of the plan is skipped without being dispatched. Neither is a
     * fault, and recording one as a failure would put a fault in the trace that did not
     * happen. Anything else the check refused stays the failure the engine reported.
     */
    private suspend fun dispatch(step: Task, priorResults: List<StepOutcome>): StepResult {
        val description = step.planStepDescription ?: step.id
        stoppedAt?.let { stopped ->
            return StepResult.Skipped(
                description = description,
                reason = "Skipped: the plan gate stopped the run (${stopped.reason})",
            )
        }
        failedDependencyOf(step)?.let { failed ->
            failedRoots[step.id] = failed
            return StepResult.skip(description = description, reason = "depends on failed step $failed")
        }

        val seat = seatFor(step)
        seatsThatRan += seat.role
        cycleSeats += seat.role
        aboutToShow(priorResults)
        val argumentGate = StepArgumentGate.forStep(step, policy.planGate)
        val result = try {
            seat.phases.withPhase(CognitivePhase.EXECUTE, runId) {
                withContext(argumentGate ?: EmptyCoroutineContext) {
                    dispatchPlanStep(
                        agent = seat.agent,
                        step = step,
                        parentTask = goal,
                        priorResults = priorResults,
                        runRules = runRules,
                    )
                }
            }
        } catch (e: CancellationException) {
            // Held, not rethrown here: `PlanExecutor` publishes this step's completed
            // pair under `NonCancellable` on the way out, so the trace says where the
            // run stopped. `execute` rethrows once the walk has unwound.
            cancellation = e
            return StepResult.failure(
                description = description,
                error = "cancelled before completing",
                isCritical = true,
            )
        } catch (e: Exception) {
            StepResult.failure(
                description = description,
                error = "Exception during step execution: ${e.message}",
            )
        }

        when (val regated = argumentGate?.decision) {
            null -> Unit
            is Regated.Approved -> gatedPlan = gatedPlan.withStepArguments(step.id, regated.arguments)
            Regated.Removed -> return StepResult.Skipped(
                description = description,
                reason = "Skipped: ${StepArgumentGate.REMOVED}",
            )
            is Regated.Stopped -> {
                stoppedAt = regated.decision
                return StepResult.Skipped(
                    description = description,
                    reason = "Skipped: the plan gate stopped the run once this step's " +
                        "arguments changed (${regated.decision.reason})",
                )
            }
        }

        if (result !is StepResult.Failure) return result
        failedRoots[step.id] = step.id
        return if (coroutineContext.isActive && !quotaReached()) result.copy(isCritical = false) else result
    }

    /**
     * Whether a run quota is reached now, read from the record as the seat's Breaker reads
     * it. A record that cannot be read answers no: the next step's own admission fails
     * closed on the same read, so nothing is let through on it.
     */
    private suspend fun quotaReached(): Boolean =
        quotaCheck?.admit(tool = null, rules = runRules)?.getOrNull() is Admission.Trip

    /**
     * The failed step [step] cannot run without, or null when it can run (AMPR-450).
     *
     * The first of its [dependencies][Task.Step.dependsOn] that failed, or was itself
     * skipped for a failure — in which case the answer is the failure at the root, so a
     * step two links from a failed one names the step that actually failed. A
     * dependency that succeeded, or that is not in the run at all because the
     * [PlanGate] trimmed it, is no reason to skip. Only a [Task.Step] declares
     * dependencies; any other task depends on nothing.
     */
    private fun failedDependencyOf(step: Task): TaskId? =
        (step as? Task.Step)?.dependsOn?.firstNotNullOfOrNull { failedRoots[it] }

    /**
     * The seat that runs [step].
     *
     * Three readings, in order of how much the planner had to get right:
     *
     * 1. The seat the plan assigned it to. `PlanGenerator` resolves the seat against
     *    the ones it was given (AMPR-410), so this is the normal answer.
     * 2. The seat named by a seat-namespaced tool id, `<seat>/<tool>` (AMPR-413) —
     *    which is the id this run offered the planner, so a step that echoed the id
     *    back names its seat whether or not it also filled in `seat`.
     * 3. The one seat that can run the named tool, when exactly one can.
     *
     * Otherwise the host seat, which is also what a step with no tool and no seat
     * means: thinking the plan did not delegate. [Roster.seatRunning] is the same
     * question asked of the roster's *declared* tool ids; this asks it of the tools
     * actually wired into each seat, which is the set that can be dispatched against.
     */
    private fun seatFor(step: Task): HostedSeat {
        (step.planStepSeat as? AssignedTo.Agent)?.let { assigned ->
            seats.values.firstOrNull { it.id == assigned.agentId }?.let { return it }
        }

        val toolId = step.planStepToolId ?: return host
        SeatTool.parse(toolId)?.let { seatTool ->
            seats[seatTool.seat]?.takeIf { it.owns(toolId) }?.let { return it }
        }
        seats.values.filter { it.owns(toolId) }.singleOrNull()?.let { return it }
        return host
    }

    // ========================================================================
    // Hold and resume
    // ========================================================================

    /**
     * Writes the held run to [heldRuns] and answers whether it is now resumable (AMPR-431).
     *
     * Everything [resume] needs to carry on is in the record: the goal, the cycle, the
     * plan as the gate approved it — with any arguments the gate approved again since —
     * every result so far, the held step and what it waits on, the host seat's ideas and
     * the seats that ran. Written under [NonCancellable], like the rest of a run's record
     * of itself: a hold that happened and was not recorded cannot be carried on.
     *
     * A failed write does not fail the run. It is still held — that is what happened — and
     * merely not resumable; the failure is logged, and is not thrown, because the caller
     * of [execute] has a held run to report either way. The record holds a person's
     * content, so the log names the run and the failure and nothing in it.
     */
    private suspend fun record(): Boolean {
        val store = heldRuns ?: return false
        val held = heldAt ?: return false
        val record = HeldRun(
            runId = runId,
            hostRole = roster.host.value,
            hostAgentId = host.id,
            goal = goal,
            cycle = cyclesUsed,
            plan = gatedPlan.let { remaining ->
                if (heldCycleHead.isNotEmpty() && remaining is Plan.ForTask) {
                    remaining.copy(tasks = heldCycleHead + remaining.tasks)
                } else {
                    remaining
                }
            },
            results = results.toList(),
            heldStepId = held.id,
            reason = held.reason,
            reference = held.reference,
            ideas = ideas,
            seatsThatRan = seatsThatRan.mapTo(mutableSetOf()) { it.value },
            heldAt = Clock.System.now(),
        )
        val written = try {
            withContext(NonCancellable) { store.hold(record) }
        } catch (e: Exception) {
            Result.failure(e)
        }
        written.onFailure { throwable ->
            logWith("RosterHostedRun").w(throwable) {
                "Run $runId held, and its record could not be written; it is held and cannot be resumed"
            }
        }
        return written.isSuccess
    }

    /**
     * Forgets the held record this run took up, once the run has ended in any status but
     * held (AMPR-431). A failed delete is logged and left: the record stays listed by
     * `RunHost.heldRuns`, claimed, for its owner to remove.
     */
    private suspend fun retireRecord() {
        if (!ownsRecord) return
        val store = heldRuns ?: return
        ownsRecord = false
        store.delete(runId).onFailure { throwable ->
            logWith("RosterHostedRun").w(throwable) {
                "Run $runId finished and its held record could not be deleted"
            }
        }
    }

    /**
     * Rebuilds this run from [record] and settles the held step with [resolution]
     * (AMPR-431, AMPR-385 rows H29–H32). `RosterRunHost.resume` has already claimed the
     * record, so this instance is the only one that will ever carry it on.
     *
     * What is restored: the goal (it reached this instance's constructor), the host
     * seat's ideas, the cycle count, the seats that ran, every result before the held
     * step, which failures later steps must not run without, and the run's task —
     * re-attached, so it is not created or started a second time. What is not: anything a
     * model said for the held cycle. No PERCEIVE, no PLAN, and no gate.
     *
     * [HoldResolution.Completed]: RECALL runs again, since the corpus may have moved while
     * the person decided, and then OBSERVE; neither calls a model. The held step becomes a
     * success carrying the consumer's report, the skips `PlanExecutor` wrote after it are
     * dropped — those steps are about to run — and [plan] becomes the steps after it,
     * which [execute] walks as the rest of the held cycle. The held step's
     * `PlanStepCompleted` goes out through the door of its seat, as it would have had the
     * step finished then, and the run's task records one `TaskProgressed`.
     *
     * [HoldResolution.Declined]: the held step becomes a critical failure whose error
     * begins `Declined:`, the skips after it stay, its `PlanStepCompleted` goes out, and
     * [plan] is empty — [execute] runs and plans nothing, and the run ends failed.
     *
     * Ampere never re-runs the held tool: the step's result is [resolution]'s, and nothing
     * here dispatches.
     */
    suspend fun resume(record: HeldRun, resolution: HoldResolution) {
        ownsRecord = true
        val approved = requireNotNull(record.plan as? Plan.ForTask) {
            "run $runId held on a plan that is not a task's plan; nothing walked it"
        }
        val heldIndex = approved.tasks.indexOfFirst { it.id == record.heldStepId }
        require(heldIndex >= 0) { "run $runId's held step ${record.heldStepId} is not in its plan" }
        val heldStep = approved.tasks[heldIndex]
        val heldResult = record.results.indexOfLast { it.id == record.heldStepId && it is StepOutcome.Held }
        require(heldResult >= 0) { "run $runId's record has no held outcome for ${record.heldStepId}" }
        val held = record.results[heldResult] as StepOutcome.Held

        host.agent.rememberNewTask(goal)
        // Idempotent: a goal the run formed before it held is neither announced nor written
        // again. A run held before it had a branch forms its goal here, so LEARN has one to close.
        formGoal()
        ideas = record.ideas
        cyclesUsed = record.cycle
        record.seatsThatRan.mapTo(seatsThatRan) { RoleId(it) }
        results += record.results.take(heldResult)
        // Whether those results were put in front of a model before the hold is not on the
        // record, so a run with any outside content behind it is taken to have shown it:
        // the error is toward a tighter write gate (AMPR-453).
        if (results.any { it.origin == ContentOrigin.External }) shownExternal = true
        restoreFailedRoots(approved.tasks.take(heldIndex))
        lifecycle = host.door.reattachTaskLifecycle(
            taskId = runId,
            runId = runId,
            taskType = MemoryTaskTypes.GENERIC,
        )

        if (resolution is HoldResolution.Completed) {
            recall()
            observe(cycle = record.cycle)
        }

        val settledAt = Clock.System.now()
        val settledStep: StepOutcome = when (resolution) {
            // The consumer's report of an action performed outside the run: `External`.
            is HoldResolution.Completed -> StepOutcome.Success(
                id = held.id,
                stepDescription = held.stepDescription,
                startTimestamp = held.startTimestamp,
                endTimestamp = settledAt,
                details = resolution.details,
                origin = ContentOrigin.External,
            )
            is HoldResolution.Declined -> StepOutcome.Failure(
                id = held.id,
                stepDescription = held.stepDescription,
                startTimestamp = held.startTimestamp,
                endTimestamp = settledAt,
                error = "Declined: ${resolution.reason}",
                isCritical = true,
                origin = ContentOrigin.External,
            )
        }
        results += settledStep
        stepWalker().publishStepCompleted(
            plan = approved,
            step = heldStep,
            index = heldIndex,
            totalSteps = approved.tasks.size,
            outcome = settledStep,
        )

        val cycleBefore = approved.tasks.take(heldIndex).mapTo(mutableSetOf()) { it.id }
        val earlier = record.results.filter { it.id in cycleBefore } + settledStep
        val remaining = approved.copy(tasks = approved.tasks.drop(heldIndex + 1))

        when (resolution) {
            is HoldResolution.Completed -> {
                gatedPlan = remaining
                heldCycleHead = approved.tasks.take(heldIndex + 1)
                resumePoint = ResumePoint(
                    firstStepIndex = heldIndex + 1,
                    totalSteps = approved.tasks.size,
                    earlierStepOutcomes = earlier,
                )
                lastPlanOutcome = foldHeldCycle(approved, heldIndex + 1, earlier)
                lifecycle?.progressed("Resumed: the held step ${held.id} was completed")
            }
            is HoldResolution.Declined -> {
                failedRoots[held.id] = held.id
                val skippedAfter = record.results.drop(heldResult + 1)
                results += skippedAfter
                declined = resolution.reason
                gatedPlan = Plan.blank
                lastPlanOutcome = foldHeldCycle(approved, approved.tasks.size, earlier + skippedAfter)
            }
        }
    }

    /**
     * Puts back which failure each step of the held cycle cannot run without (AMPR-450),
     * from the outcomes before the held step: a failure roots itself, and a step skipped
     * for a failed dependency roots where that dependency does. Read off the plan's own
     * `dependsOn`, as [dispatch] reads it, rather than off a skip's text.
     */
    private fun restoreFailedRoots(before: List<Task>) {
        results.forEach { if (it is StepOutcome.Failure) failedRoots[it.id] = it.id }
        before.forEach { step ->
            if (step.id in failedRoots) return@forEach
            val skipped = results.any { it.id == step.id && it is StepOutcome.Skipped }
            if (skipped) failedDependencyOf(step)?.let { failedRoots[step.id] = it }
        }
    }

    /**
     * The held cycle's outcome as it stands, with [walked] in place of its steps up to
     * [walkedUpTo] and nothing walked beyond. `PlanExecutor` folds it so it reads exactly as
     * an unbroken cycle's would; nothing is dispatched or published.
     */
    private suspend fun foldHeldCycle(approved: Plan.ForTask, walkedUpTo: Int, walked: List<StepOutcome>): Outcome =
        PlanExecutor(runId = runId).execute(
            plan = approved.copy(tasks = emptyList()),
            priorResults = results.toList(),
            firstStepIndex = walkedUpTo,
            totalSteps = approved.tasks.size,
            earlierStepOutcomes = walked,
        ) { _, _ -> error("a fold walks no step") }.outcome

    // ========================================================================
    // LEARN
    // ========================================================================

    /**
     * The LEARN that may close a cycle (AMPR-484, epic AMPR-478 row N8).
     *
     * Called after [assess], before the loop decides whether to continue, for a cycle that
     * executed and neither held nor was stopped. It writes when the cycle's walk settled to an
     * [Outcome.Success] and the cadence says so: [LearnCadence.EveryCycle] always,
     * [LearnCadence.WhenLesson] when the lesson judgment the receptor's request came back with
     * is `MEASURED` and puts `keepAbove` or more on `true`. Then the host seat opens one LEARN
     * bracket and every seat that ran a step *in this cycle* stores one `Knowledge.FromOutcome`
     * — `KnowledgeExtractor.extractDefault` over the cycle's outcome and plan, no model call —
     * through its own `AgentMemoryService` under the run id, tagged with its role and
     * `cycle:<n>`, so the `KnowledgeStored` names the holder (F1, F9, F11) and a reader can tell
     * a cycle's row from the close-time one.
     *
     * Nothing is written for a cycle whose walk did not settle to a success, a cancelled run,
     * or — under `WhenLesson` — a lesson judgment that is missing or carries no measured
     * distribution: a lesson is a claim, and the quiet direction is the safe one. The
     * `Succeeded`-only rule of the close-time LEARN (invariant 17) is deliberately not applied
     * to a cycle: that rule is about a *run* that finished its work, and this is about a cycle
     * that finished its own, whatever the run goes on to do. What is stored here stays, and
     * [learnWithin] is what it was: one outcome row, Knowledge for a `Succeeded` run only.
     *
     * A seat with no memory stores nothing, as at `close()`; the bracket is still entered, as a
     * LEARN that found nowhere to write is still a LEARN.
     */
    private suspend fun learnCycle() {
        if (cancellation != null || !coroutineContext.isActive) return
        val outcome = lastPlanOutcome
        if (outcome !is Outcome.Success) return
        val stores = when (val cadence = policy.cadence.learn) {
            LearnCadence.AtClose, LearnCadence.Record -> false
            LearnCadence.EveryCycle -> true
            is LearnCadence.WhenLesson -> lessonWorthKeeping(cadence.keepAbove)
        }
        if (!stores) return

        val cycle = cyclesUsed
        val cyclePlan = gatedPlan
        host.phases.withPhase(CognitivePhase.LEARN, runId) {
            cycleSeats.mapNotNull { seats[it] }.forEach { seat ->
                seat.memory?.storeKnowledge(
                    knowledge = seat.agent.extractKnowledgeFromOutcome(outcome, goal, cyclePlan),
                    tags = listOf(seat.role.value, "$CYCLE_TAG_PREFIX$cycle"),
                    taskType = MemoryTaskTypes.GENERIC,
                    runId = runId,
                )
            }
        }
    }

    /**
     * Whether the latest cycle's lesson judgment says keep: `MEASURED`, with `keepAbove` or more
     * on `true`. Missing, self-reported or without mass on `true` is no.
     */
    private fun lessonWorthKeeping(keepAbove: Double): Boolean {
        val judgment = lessonJudgment ?: return false
        val distribution = judgment.distribution ?: return false
        if (judgment.source != ConfidenceSource.MEASURED) return false
        val keep = distribution[Question.Noul.TRUE_KEY] ?: return false
        return keep >= keepAbove
    }

    override suspend fun close() {
        // Read before the `NonCancellable` hop, which is what makes the rest of this
        // method run at all: inside it, the caller's cancellation is no longer visible.
        val cancelledByCaller = !coroutineContext.isActive

        withContext(NonCancellable) {
            if (closed) return@withContext
            closed = true
            try {
                learn(cancelled = cancellation != null || cancelledByCaller)
            } finally {
                onClosed(this@RosterHostedRun)
            }
        }
    }

    /**
     * The close-time LEARN: once per run. A cycle may learn too, under the consumer's
     * [LearnCadence] ([learnCycle], AMPR-484); nothing here reads or undoes what it stored.
     *
     * Three writes, in order. One `recordOutcome` for the run as a whole, keyed by the
     * run id and attributed to the host seat — its first production caller in this
     * repo beside the per-tool rows the engine records (AMPR-406). Then, for each seat
     * that executed a step, `KnowledgeExtractor.extractDefault` through that seat's own
     * `AgentMemoryService.storeKnowledge`, which publishes its `KnowledgeStored`
     * through the seat's door under the run. Then the run's task ends.
     *
     * No model call in any of that. [LearnPolicy.ModelBacked] adds one — the host
     * seat's `OutcomeEvaluator` — and is opt-in because it bills.
     *
     * The outcome is recorded for every run that is not held; Knowledge — the extracted
     * learnings and the model-backed evaluation alike — only for a [RunStatus.Succeeded]
     * one (AMPR-450, row H20). The outcome is what happened, and Knowledge is a claim
     * about what was learned from work that finished: a failed, partial, stopped or
     * cancelled run did not finish its work, so it has nothing to claim.
     *
     * A held run writes nothing at all (AMPR-425). The phase is still entered and
     * bracketed — a LEARN that found nothing to learn is still a LEARN — but there is no
     * outcome row, because the store can only say succeeded or failed and the run did
     * neither; no Knowledge, because the work did not finish; no model-backed evaluation;
     * and no terminal task event, because the run is waiting, not finished. Its task was
     * marked blocked when the step held, and stays that way. That is decided by the run's
     * own status, so a caller that cancels its scope after a held [execute] and closes
     * the run in a `finally` does not turn the hold into a failure. Its held record stays.
     *
     * A resumed run is one run (AMPR-431): its LEARN is for the whole of it, so the outcome
     * folds every result before the hold and after, Knowledge comes from every seat that
     * ran on either side, and the terminal task event closes the task the run opened
     * before it held. Closing it in any status but held deletes the record it took up.
     *
     * On a run with a [GoalBranch] (AMPR-473), LEARN also closes the run's root goal before
     * anything else is written: completed for a [RunStatus.Succeeded] run that was not
     * cancelled, abandoned for every other end. A held run's goal stays open — LEARN returns
     * before it — and the resumed run's LEARN is the one that closes it.
     */
    private suspend fun learn(cancelled: Boolean) {
        host.phases.withPhase(CognitivePhase.LEARN, runId) {
            learnWithin(cancelled)
        }
    }

    private suspend fun learnWithin(cancelled: Boolean) {
        val status = currentStatus()
        if (status == RunStatus.Held) return
        retireRecord()
        closeGoal(met = !cancelled && status == RunStatus.Succeeded)

        val outcome = executionOutcome(cancelled)

        host.descriptor.memory?.outcomes?.recordOutcome(
            ticketId = runId,
            // The host seat planned the run; the run as a whole is what performed it
            // (AMPR-443). The seat's id used to be written as the executor.
            actorId = host.id,
            executorId = HOSTED_RUN_EXECUTOR_ID,
            approach = approachText(),
            outcome = outcome,
            timestamp = Clock.System.now(),
            runId = runId,
        )

        if (!cancelled && status == RunStatus.Succeeded) {
            seatsThatRan.mapNotNull { seats[it] }.forEach { seat ->
                seat.memory?.storeKnowledge(
                    knowledge = seat.agent.extractKnowledgeFromOutcome(outcome, goal, gatedPlan),
                    tags = listOf(seat.role.value),
                    taskType = MemoryTaskTypes.GENERIC,
                    runId = runId,
                )
            }

            if (policy.learn == LearnPolicy.ModelBacked) {
                host.agent.reasoningUnit.evaluateOutcomes(
                    outcomes = listOf(outcome),
                    memoryService = host.memory,
                    runId = runId,
                )
            }
        }

        if (cancelled) {
            lifecycle?.failed("Cancelled before completing")
        } else {
            when (val stopped = stoppedAt) {
                // A partial run did not do what it was asked: some of its work failed, so
                // its task did too, as it did before a partial run had a status of its own.
                null ->
                    if (status == RunStatus.Failed || status == RunStatus.Partial) {
                        lifecycle?.failed(outcome.describeForRecord())
                    } else {
                        lifecycle?.completed(outcome.describeForRecord())
                    }
                // A stopped run did what it was told, which was to stop, so its task
                // completed. What it was told is the summary.
                else -> lifecycle?.completed(stopped.reason)
            }
        }
    }

    // ========================================================================
    // Reporting
    // ========================================================================

    fun openSeats(): List<OpenSeat> = roster.all().mapNotNull { role ->
        seats[role.id]?.let { seat ->
            OpenSeat(
                runId = runId,
                role = seat.role,
                agentId = seat.id,
                isHost = seat.role == roster.host,
                goal = goalDescription(),
                sparkNames = seat.descriptor.sparks.map { it.name },
            )
        }
    }

    /**
     * How the run ended, in precedence order (AMPR-425, AMPR-450): cancelled, declined at a
     * hold (AMPR-431), stopped by the gate, held, then by its steps — none failed is succeeded, some failed beside
     * some that succeeded is partial, and failures with no success is failed. A hold
     * outranks an earlier cycle's failure because it is why the run ended. A plan cut to
     * nothing (AMPR-452) counts as a failure beside the steps: the run could not attempt
     * what its planner asked for, so it did not succeed, and did part of its work only if
     * an earlier cycle's step succeeded.
     */
    private fun currentStatus(): RunStatus = when {
        cancellation != null -> RunStatus.Failed
        // A declined hold is the run not doing what it was asked, whatever ran before it.
        declined != null -> RunStatus.Failed
        stoppedAt != null -> RunStatus.Stopped
        heldAt != null -> RunStatus.Held
        results.none { it is StepOutcome.Failure } && cutToNothing == null -> RunStatus.Succeeded
        results.any { it is StepOutcome.Success || it is StepOutcome.PartialSuccess } -> RunStatus.Partial
        else -> RunStatus.Failed
    }

    /**
     * The run as episodic memory records it.
     *
     * [PlanExecutor]'s own outcome when the run executed something — that one already
     * names every failed step and what it said — and a stated one otherwise, because a
     * run that was stopped, cancelled, or planned into nothing still happened and the
     * store is where that is visible.
     *
     * For a held run that is the held cycle's outcome — the run stops on the cycle that
     * held, so the last plan outcome *is* the [ExecutionOutcome.Held], carrying the held
     * step's reason and reference. [RunResult.outcome] reports it; LEARN records nothing.
     *
     * A run whose last plan was cut to nothing (AMPR-452) is a stated failure naming the
     * cut, even after earlier cycles ran: that cut is why the run ended.
     */
    private fun executionOutcome(cancelled: Boolean): ExecutionOutcome {
        val cut = cutToNothing
        (lastPlanOutcome as? ExecutionOutcome)
            ?.takeIf { results.isNotEmpty() && !cancelled && cut == null }
            ?.let { return it }

        val now = Clock.System.now()
        val stopped = stoppedAt
        val reason = when {
            cancelled -> "Run cancelled after ${results.size} step(s)"
            stopped != null -> stopped.reason
            cut != null -> "Cycle ${cut.cycle}'s plan was cut to nothing: ${cut.reasons.joinToString("; ")}"
            else -> "The planner returned no steps"
        }
        return if (cancelled || cut != null) {
            ExecutionOutcome.NoChanges.Failure(
                executorId = HOSTED_RUN_EXECUTOR_ID,
                ticketId = runId,
                taskId = goal.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = reason,
            )
        } else {
            ExecutionOutcome.NoChanges.Success(
                executorId = HOSTED_RUN_EXECUTOR_ID,
                ticketId = runId,
                taskId = goal.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = reason,
            )
        }
    }

    private fun approachText(): String = buildString {
        append("Hosted run over roster host '${roster.host.value}' (${seats.size} seat(s)), ")
        append("$cyclesUsed cycle(s): ")
        append(goalDescription())
    }

    private fun goalDescription(): String =
        goal.planStepDescription ?: goal.id.ifBlank { "unnamed goal" }

    companion object {
        /**
         * The executor a hosted run's own outcome is attributed to (AMPR-443): the run, not
         * any one seat, since several seats may have performed its steps — the shape an Arc
         * run's `arc:<name>` has. The host seat is the row's actor. `outcomes executor
         * hosted-run` lists every hosted run's own row.
         */
        const val HOSTED_RUN_EXECUTOR_ID: ExecutorId = "hosted-run"

        /**
         * The prefix of the tag a cycle's LEARN puts on each row it stores (AMPR-484):
         * `cycle:<n>`, beside the seat's role. A close-time row carries the role alone.
         */
        const val CYCLE_TAG_PREFIX: String = "cycle:"

        /**
         * The node id of a hosted run's one goal (AMPR-473), the id `GoalTreeBuilder` gives an
         * Arc run's root — so on the branch it is `<run id>/goal-0` either way.
         */
        const val ROOT_GOAL_NODE: String = "goal-0"
    }
}

/** What an outcome says it did, for a summary a person reads. */
private fun ExecutionOutcome.describeForRecord(): String =
    describeResult().ifBlank { this::class.simpleName ?: "outcome" }

/** A plan held to a run's bounds, and the cut that got it there, if one did (AMPR-452). */
private class BoundedPlan(
    val plan: Plan,
    val truncation: PlanEvent.PlanTruncated? = null,
)

/** The bounds a plan broke: too many steps for [cap], tools no seat has, or both. */
private class BrokenBounds(
    val stepCount: Int,
    val cap: Int,
    val unknownToolIds: List<String>,
) {

    val overCap: Boolean get() = stepCount > cap

    /**
     * What the planner is told on its one re-plan: the rule, the number or the ids that
     * broke it, and what an acceptable plan looks like. The ids are the planner's own
     * output read back to it, not content from outside the run.
     */
    fun asCorrection(): Idea = Idea(
        name = "Correction to the previous plan",
        description = buildString {
            append("The previous plan for this cycle cannot be run as written.")
            if (overCap) {
                append(" It had $stepCount steps, and this run allows at most $cap steps per cycle:")
                append(" plan at most $cap steps.")
            }
            if (unknownToolIds.isNotEmpty()) {
                append(" It named tools this run does not have: ${unknownToolIds.joinToString(", ")}.")
                append(" Name only the tools listed below, or no tool for a step that is reasoning.")
            }
        },
    )
}

/** This plan with [kept] as its steps. A blank plan has none to keep and stays blank. */
private fun Plan.withTasks(kept: List<Task>): Plan = when (this) {
    is Plan.ForTask -> copy(tasks = kept)
    is Plan.ForIdea -> copy(tasks = kept)
    is Plan.ForMeeting -> copy(tasks = kept)
    is Plan.ForTicket -> copy(tasks = kept)
    Plan.Blank -> this
}

/**
 * Where a resumed run's first EXECUTE picks the held cycle up (AMPR-431).
 *
 * @property firstStepIndex The first remaining step's index in the approved plan.
 * @property totalSteps The approved plan's step count.
 * @property earlierStepOutcomes The held cycle's outcomes up to and including the settled
 *   held step, which the walk folds with the remaining steps'.
 */
private class ResumePoint(
    val firstStepIndex: Int,
    val totalSteps: Int,
    val earlierStepOutcomes: List<StepOutcome>,
)
