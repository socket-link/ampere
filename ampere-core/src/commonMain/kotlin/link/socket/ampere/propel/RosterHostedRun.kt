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

/**
 * One run over one roster (AMPR-385 rows H1, H6).
 *
 * The host seat charges PERCEIVE → RECALL → OBSERVE → PLAN; [execute] runs the gated
 * plan a step at a time on the seat each step was assigned to, re-entering OBSERVE →
 * PLAN while cycles remain; [close] is LEARN.
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
     * RECALL, on the host seat's own memory, scoped to the goal.
     *
     * What it finds goes to PLAN (row H4a, AMPR-388) and nowhere else yet: rendering
     * recalled items into the seat's active prompt as a "Recalled context" section,
     * and the `RecallSource` SPI that would let a consumer's own corpus join in, are
     * AMPR-394's.
     */
    private suspend fun recall() {
        host.phases.withPhase(CognitivePhase.RECALL, runId) {
            recalled = host.agent.recallForRun(goal)
        }
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
     */
    private suspend fun planCycle(): PlanDecision =
        host.phases.withPhase(CognitivePhase.PLAN, runId) {
            val generated = host.agent.reasoningUnit.generatePlan(
                task = goal,
                ideas = ideas,
                relevantKnowledge = recalled,
                seats = planSeats,
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

            when (val decision = policy.planGate.review(generated)) {
                is PlanDecision.Approved -> {
                    gatedPlan = decision.plan
                    stoppedAt = null
                    decision
                }
                is PlanDecision.Stopped -> {
                    // The plan is dropped rather than kept-but-unrun: `plan` is what is
                    // about to happen, and after a stop that is nothing.
                    gatedPlan = Plan.blank
                    stoppedAt = decision
                    decision
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
            }
            captureCancellation()
            if (cancellation != null || stoppedAt != null) break
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
     */
    private suspend fun learn(cancelled: Boolean) {
        host.phases.withPhase(CognitivePhase.LEARN, runId) {
            learnWithin(cancelled)
        }
    }

    private suspend fun learnWithin(cancelled: Boolean) {
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

    private fun currentStatus(): RunStatus = when {
        cancellation != null -> RunStatus.Failed
        stoppedAt != null -> RunStatus.Stopped
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
