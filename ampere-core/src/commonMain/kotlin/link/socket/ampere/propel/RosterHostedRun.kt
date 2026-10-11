package link.socket.ampere.propel

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
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.PlanExecutor
import link.socket.ampere.agents.domain.reasoning.PlanSeat
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.agents.domain.task.planStepSeat
import link.socket.ampere.agents.domain.task.planStepToolId
import link.socket.ampere.agents.events.api.TaskLifecycle
import link.socket.ampere.agents.events.api.openTaskLifecycle
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.describeResult
import link.socket.ampere.llm.DispatchingUpstreamLlmClient
import link.socket.ampere.probe.ProbeSuite
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
 *
 * A cycle that executed something may also be *asked* about itself before the loop
 * decides whether to continue ([assess], AMPR-421). Every answer is recorded and none
 * is read: the loop, [currentStatus] and LEARN are the same with a judgment as without
 * one, which is the whole of what makes the question affordable to ask this early.
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
 * One run, one driver: the fields below are not synchronised, and two coroutines
 * driving the same run would interleave its phases.
 */
internal class RosterHostedRun(
    override val runId: RunId,
    private val roster: Roster,
    private val seats: Map<RoleId, HostedSeat>,
    private val goal: Task,
    private val policy: RunPolicy,
    private val onClosed: (RosterHostedRun) -> Unit,
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
     */
    private val planSeats: List<PlanSeat> = roster.all().mapNotNull { seats[it.id]?.asPlanSeat() }

    private var gatedPlan: Plan = Plan.blank
    private var stoppedAt: PlanDecision.Stopped? = null
    private var heldAt: StepOutcome.Held? = null
    private var lifecycle: TaskLifecycle? = null
    private var ideas: List<Idea> = emptyList()
    private var recalled: List<KnowledgeWithScore> = emptyList()
    private val results = mutableListOf<StepOutcome>()
    private val seatsThatRan = mutableSetOf<RoleId>()
    private var cyclesUsed: Int = 0
    private var lastPlanOutcome: Outcome = Outcome.blank
    private var cancellation: CancellationException? = null
    private var settled: RunResult? = null
    private var closed: Boolean = false

    override val plan: Plan
        get() = gatedPlan

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
        perceive()
        recall()
        observe(cycle = 1)
        planCycle()
        cyclesUsed = 1
    }

    private suspend fun perceive() {
        host.phases.withPhase(CognitivePhase.PERCEIVE, runId) {
            // The stack is built and on the record before anything reasons with it: one
            // `SparkAppliedEvent` per spark as the seat was constructed, and this
            // snapshot of what they added up to.
            host.agent.emitCognitiveSnapshot()

            val perception = Perception(
                id = generateUUID("perception", host.id),
                currentState = host.agent.getCurrentState(),
                ideas = ideas,
            )
            host.agent.rememberNewPerception(perception)
            val idea = host.agent.reasoningUnit.evaluatePerception(perception)
            host.agent.rememberNewIdea(idea)
            ideas = listOf(idea)
        }
    }

    /**
     * RECALL: the host seat's own memory and the consumer's corpora, scoped to the goal
     * (rows H4a, H4b).
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
     */
    private suspend fun recall() {
        host.phases.withPhase(CognitivePhase.RECALL, runId) {
            val recallPolicy = policy.recall
            val scopes = host.descriptor.recallScopes
            val context = host.agent.recallContextForRun(goal, scopes)

            val found = RecalledContext(
                fromMemory = host.agent.recallForRun(context, recallPolicy.memoryLimit),
                fromSources = if (scopes.isEmpty()) {
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
            tellSeats(found.promptSection(recallPolicy.titleClip, recallPolicy.textClip))
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
     */
    private suspend fun planCycle(): Unit =
        host.phases.withPhase(CognitivePhase.PLAN, runId) {
            val generated = host.agent.reasoningUnit.generatePlan(
                task = goal,
                ideas = ideas,
                relevantKnowledge = recalled,
                seats = planSeats,
                priorResults = results.toList(),
                cycle = cyclesUsed + 1,
            )
            host.agent.rememberNewPlan(generated)

            if (lifecycle == null) {
                lifecycle = host.door.openTaskLifecycle(
                    taskId = runId,
                    description = goalDescription(),
                    runId = runId,
                    assignedTo = host.id,
                    taskType = MemoryTaskTypes.GENERIC,
                )
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

    // ========================================================================
    // EXECUTE, and the cycles
    // ========================================================================

    override suspend fun execute(): RunResult {
        settled?.let { return it }

        while (true) {
            if (stoppedAt == null && gatedPlan.tasks.isNotEmpty()) {
                runCycle(gatedPlan)
                // A held cycle is not asked whether the goal is met: it is waiting, and
                // the run has already decided that nothing follows it.
                if (heldAt == null) assess()
            }
            captureCancellation()
            // A hold is a full stop, whatever `cycles` says: the next PLAN would be planning
            // past a step whose output does not exist yet (AMPR-425).
            if (cancellation != null || stoppedAt != null || heldAt != null) break
            // The cap and the planner are the two ways a run ends: whichever comes
            // first. An empty plan means the host had nothing left to ask for.
            if (cyclesUsed >= policy.cycles || gatedPlan.tasks.isEmpty()) break

            observe(cycle = cyclesUsed + 1)
            planCycle()
            cyclesUsed++
        }

        val result = RunResult(
            runId = runId,
            status = currentStatus(),
            cyclesUsed = cyclesUsed,
            stepOutcomes = results.toList(),
            outcome = executionOutcome(cancelled = cancellation != null),
        )
        settled = result
        cancellation?.let { throw it }
        return result
    }

    /**
     * One EXECUTE: every step of [plan], each on the seat it was assigned to.
     *
     * [PlanExecutor] does the walking — the sequential order, the running chain of
     * results, the stop on a critical failure and the skipping of what came after it,
     * and the `PlanStepStarted` / `PlanStepCompleted` pair per step (row H5). It is
     * handed a door per step rather than one door, so each pair leaves through the
     * seat that ran the step; the run-level door is the fallback for a step no seat
     * claimed.
     *
     * Dispatch itself goes through `SparkBasedAgent.executePlanStep`, the same entry
     * the Arc path's Execute uses. That is what keeps one definition of what executing
     * a step means: strict tool-id routing against the seat's spark-narrowed tool set
     * (AMPR-400), a tool-less step carried out as that seat's own model call
     * (AMPR-407), the earlier steps' results on the request (AMPR-408, AMPR-412), and
     * a step's inline arguments settling the call without a parameter model call
     * (AMPR-410, AMPR-411). None of it is re-implemented here.
     *
     * A held step is noticed here, not decided here: `PlanExecutor` has already stopped
     * the walk on it and skipped the rest (AMPR-425). What the run adds is the record —
     * its task is marked blocked on the held step, which is the state a resumed run would
     * pick up — and [execute]'s decision not to plan again.
     */
    private suspend fun runCycle(toRun: Plan) {
        val execution = host.phases.withPhase(CognitivePhase.EXECUTE, runId) {
            PlanExecutor(
                executorId = host.id,
                eventApi = host.door,
                runId = runId,
                stepEventApi = { step -> seatFor(step).door },
            ).execute(
                plan = toRun,
                priorResults = results.toList(),
            ) { step, context ->
                dispatch(step, context.priorResults)
            }
        }

        results += execution.stepOutcomes
        lastPlanOutcome = execution.outcome

        execution.stepOutcomes.firstNotNullOfOrNull { it as? StepOutcome.Held }?.let { held ->
            heldAt = held
            lifecycle?.blocked(blockedByTaskId = held.id, reason = held.reason)
        }
    }

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
     */
    private suspend fun assess() {
        if (cancellation != null || !coroutineContext.isActive) return

        val asksGoal = policy.goalCheck == GoalCheck.Record
        val verifier = roster.verifier
            ?.let { seats[it] }
            ?.takeIf { it.descriptor.questions.isNotEmpty() }
        if (!asksGoal && verifier == null) return

        host.phases.withPhase(CognitivePhase.OBSERVE, runId) {
            val state = RunObservation(
                runId = runId,
                goal = goal,
                cycle = cyclesUsed,
                priorResults = results.toList(),
            ).asDecisionState(policy.judgmentResultClip)

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

    private suspend fun dispatch(step: Task, priorResults: List<StepOutcome>): StepResult {
        val seat = seatFor(step)
        seatsThatRan += seat.role
        return try {
            seat.phases.withPhase(CognitivePhase.EXECUTE, runId) {
                seat.agent.executePlanStep(
                    step = step,
                    parentTask = goal,
                    priorResults = priorResults,
                )
            }
        } catch (e: CancellationException) {
            // Held, not rethrown here: `PlanExecutor` publishes this step's completed
            // pair under `NonCancellable` on the way out, so the trace says where the
            // run stopped. `execute` rethrows once the walk has unwound.
            cancellation = e
            StepResult.failure(
                description = step.planStepDescription ?: step.id,
                error = "cancelled before completing",
                isCritical = true,
            )
        }
    }

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
    // LEARN
    // ========================================================================

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
     * LEARN: once per run, never once per cycle.
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
     * A cancelled run records its outcome and stores no Knowledge: the outcome is what
     * happened, and Knowledge is a claim about what was learned from work that
     * finished.
     *
     * A held run writes nothing at all (AMPR-425). The phase is still entered and
     * bracketed — a LEARN that found nothing to learn is still a LEARN — but there is no
     * outcome row, because the store can only say succeeded or failed and the run did
     * neither; no Knowledge, because the work did not finish; no model-backed evaluation;
     * and no terminal task event, because the run is waiting, not finished. Its task was
     * marked blocked when the step held, and stays that way. That is decided by the run's
     * own status, so a caller that cancels its scope after a held [execute] and closes
     * the run in a `finally` does not turn the hold into a failure.
     */
    private suspend fun learn(cancelled: Boolean) {
        host.phases.withPhase(CognitivePhase.LEARN, runId) {
            learnWithin(cancelled)
        }
    }

    private suspend fun learnWithin(cancelled: Boolean) {
        if (currentStatus() == RunStatus.Held) return

        val outcome = executionOutcome(cancelled)

        host.descriptor.memory?.outcomes?.recordOutcome(
            ticketId = runId,
            executorId = host.id,
            approach = approachText(),
            outcome = outcome,
            timestamp = Clock.System.now(),
            runId = runId,
        )

        if (!cancelled) {
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
                null ->
                    if (currentStatus() == RunStatus.Failed) {
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
     * How the run ended, in precedence order (AMPR-425): cancelled, stopped by the gate,
     * held, failed, succeeded. A hold outranks an earlier cycle's failure because it is
     * why the run ended.
     */
    private fun currentStatus(): RunStatus = when {
        cancellation != null -> RunStatus.Failed
        stoppedAt != null -> RunStatus.Stopped
        heldAt != null -> RunStatus.Held
        results.any { it is StepOutcome.Failure } -> RunStatus.Failed
        else -> RunStatus.Succeeded
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
     */
    private fun executionOutcome(cancelled: Boolean): ExecutionOutcome {
        (lastPlanOutcome as? ExecutionOutcome)?.takeIf { results.isNotEmpty() && !cancelled }?.let { return it }

        val now = Clock.System.now()
        val stopped = stoppedAt
        val reason = when {
            cancelled -> "Run cancelled after ${results.size} step(s)"
            stopped != null -> stopped.reason
            else -> "The planner returned no steps"
        }
        return if (cancelled) {
            ExecutionOutcome.NoChanges.Failure(
                executorId = host.id,
                ticketId = runId,
                taskId = goal.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = reason,
            )
        } else {
            ExecutionOutcome.NoChanges.Success(
                executorId = host.id,
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
}

/** What an outcome says it did, for a summary a person reads. */
private fun ExecutionOutcome.describeForRecord(): String =
    describeResult().ifBlank { this::class.simpleName ?: "outcome" }
