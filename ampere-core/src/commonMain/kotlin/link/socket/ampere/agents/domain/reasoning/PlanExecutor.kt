package link.socket.ampere.agents.domain.reasoning

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.outcome.TrippedRule
import link.socket.ampere.agents.domain.outcome.originOfAll
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.planStepDescription
import link.socket.ampere.agents.domain.task.planStepSeat
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.ExecutorId

/**
 * Orchestrates the execution of multi-step plans.
 *
 * This component handles the "Operate" phase of the PROPEL cognitive loop,
 * managing the sequential execution of plan steps with proper error handling,
 * step tracking, and outcome aggregation.
 *
 * Features:
 * - Sequential step execution with dependency tracking
 * - Critical failure detection and early termination
 * - A held step — one waiting on a person — ends the walk too (AMPR-425)
 * - Step outcome collection for analysis
 * - Context passing between steps: the running chain of
 *   [StepContext.priorResults] (AMPR-408), plus whatever a step names in
 *   `StepResult.contextUpdates` (e.g., created issue IDs)
 *
 * Usage:
 * ```kotlin
 * val executor = PlanExecutor()
 * val result = executor.execute(plan, priorResults = emptyList()) { step, context ->
 *     when (step) {
 *         is PMTask.CreateIssues -> executeCreateIssues(step, context)
 *         is PMTask.AssignTask -> executeAssignTask(step, context)
 *         else -> StepResult.skip("Unknown step type")
 *     }
 * }
 * ```
 *
 * @property executorId What the plan's own summary outcome is attributed to: [PLAN_EXECUTOR_ID]
 *   unless a caller names something else. Not the agent (AMPR-443, F17) — a plan's summary
 *   was produced by the walk, not by any one executor, and each step's own outcome already
 *   names the executor that ran it. Callers used to pass the agent's id here.
 * @property eventApi The agent's event door (AMPR-389). With one, each executed step
 *   leaves a [PlanEvent.PlanStepStarted] / [PlanEvent.PlanStepCompleted] pair under
 *   [runId], so an Arc run's trace shows the steps Execute actually ran. Null keeps the
 *   silence this class had before — a plan executed outside a run, or by a caller with
 *   no door, publishes nothing.
 * @property runId The Arc run the plan belongs to. Goes on both events and on their
 *   envelope, which is what `ArcTraceProjection` joins on.
 */
class PlanExecutor(
    private val executorId: ExecutorId = PLAN_EXECUTOR_ID,
    private val eventApi: AgentEventApi? = null,
    private val runId: RunId? = null,
    private val stepEventApi: ((Task) -> AgentEventApi?)? = null,
) {

    /**
     * The door [step]'s pair leaves through: the one [stepEventApi] names for it,
     * else [eventApi].
     *
     * One plan, several publishers, because a roster-hosted run's steps run on
     * different seats and a seat's provenance *is* its door's identity (AMPR-393).
     * Without this the whole plan's pairs would be attributed to whoever walked it,
     * and the trace could not say which seat ran which step — only which seat the
     * step was assigned to, which is a different claim. A caller with one executor
     * passes no resolver and every pair leaves through [eventApi], as before.
     */
    private fun doorFor(step: Task): AgentEventApi? = stepEventApi?.invoke(step) ?: eventApi

    /**
     * Executes a plan step by step.
     *
     * @param plan The plan to execute
     * @param priorResults What already happened before this plan's first step — the
     *   results of the steps the caller has run, oldest first (AMPR-408). It seeds
     *   [StepContext.priorResults], so a step executor reads the whole chain from one
     *   place no matter how the plan reached it. The live Execute path matters here:
     *   since AMPR-396 it wraps each step of the Plan phase's output in its own
     *   one-step plan, so without a seed every step would see an empty chain. Required
     *   rather than defaulted for the same reason `generatePlan`'s `relevantKnowledge`
     *   is (AMPR-388): a caller with nothing to pass says `emptyList()` and says it on
     *   purpose.
     * @param stepExecutor Function to execute individual steps
     * @return PlanExecutionResult with overall outcome and step details
     */
    suspend fun execute(
        plan: Plan,
        priorResults: List<StepOutcome>,
        stepExecutor: suspend (Task, StepContext) -> StepResult,
    ): PlanExecutionResult = execute(
        plan = plan,
        priorResults = priorResults,
        firstStepIndex = 0,
        totalSteps = plan.tasks.size,
        earlierStepOutcomes = emptyList(),
        stepExecutor = stepExecutor,
    )

    /**
     * Executes the steps of [plan] that remain of a plan already part-walked (AMPR-431).
     *
     * The same walk as the overload above, for a caller that ran the plan's first steps
     * earlier — a hosted run carrying on after a hold, possibly in another process. A
     * separate overload rather than defaulted parameters, so a caller passing the step
     * executor positionally keeps compiling.
     *
     * @param plan The steps still to run, as a plan: the approved plan with the steps
     *   before [firstStepIndex] removed.
     * @param priorResults As above: everything that happened before [plan]'s first step.
     * @param firstStepIndex Where [plan]'s first step stood in the plan a person approved,
     *   counted from 0 (AMPR-431). A hosted run that resumes after a hold walks only the
     *   steps after the held one, and its `PlanStepStarted.stepIndex` and `totalSteps` are
     *   those of the approved plan, so a reader of the trace sees step 3 of 5 and not step
     *   0 of 2.
     * @param totalSteps How many steps the approved plan has.
     * @param earlierStepOutcomes What the approved plan's steps before [firstStepIndex]
     *   ended as, oldest first. Not walked and not published — they have been — but folded
     *   into the overall outcome beside the steps walked here, so a plan walked in two
     *   parts settles to the outcome it would have settled to in one. A walk of no steps
     *   with earlier outcomes folds those alone.
     * @param stepExecutor Function to execute individual steps
     * @return PlanExecutionResult with overall outcome and the details of the steps walked
     *   here — [earlierStepOutcomes] are not repeated in its `stepOutcomes`
     */
    suspend fun execute(
        plan: Plan,
        priorResults: List<StepOutcome>,
        firstStepIndex: Int,
        totalSteps: Int,
        earlierStepOutcomes: List<StepOutcome>,
        stepExecutor: suspend (Task, StepContext) -> StepResult,
    ): PlanExecutionResult {
        val startTime = Clock.System.now()
        val stepOutcomes = mutableListOf<StepOutcome>()
        val context = MutableStepContext(priorResults)

        // Handle empty or non-task plans
        if (plan !is Plan.ForTask || (plan.tasks.isEmpty() && earlierStepOutcomes.isEmpty())) {
            val taskId = if (plan is Plan.ForTask) plan.task.id else ""
            return PlanExecutionResult(
                outcome = ExecutionOutcome.NoChanges.Success(
                    executorId = executorId,
                    ticketId = "",
                    taskId = taskId,
                    message = "Plan has no steps to execute",
                    executionStartTimestamp = startTime,
                    executionEndTimestamp = Clock.System.now(),
                    origin = ContentOrigin.Own,
                ),
                stepOutcomes = emptyList(),
                context = context.toImmutable(),
            )
        }

        // Execute steps sequentially
        for ((offset, step) in plan.tasks.withIndex()) {
            val index = firstStepIndex + offset
            val stepStartTime = Clock.System.now()
            publishStepStarted(plan, step, index, totalSteps, stepStartTime)

            // The step runs with its plan in its context, which is how the admission
            // `dispatchPlanStep` records before the step runs names the plan (AMPR-472).
            // What the step executor returned is kept even when the walk was cancelled
            // while it ran: leaving `withContext` would otherwise swap that answer — a
            // hosted run's "cancelled before completing" — for the cancellation, and the
            // record of where the walk stopped would read differently than it did.
            var returned: StepResult? = null
            val stepResult = try {
                withContext(PlanWalk(plan.id)) {
                    stepExecutor(step, context.toImmutable()).also { returned = it }
                }
            } catch (e: Exception) {
                returned ?: StepResult.failure(
                    description = "Execute step ${step.id}",
                    error = "Exception during step execution: ${e.message}",
                    isCritical = true,
                )
            }

            // Convert StepResult to StepOutcome
            val stepOutcome = stepResult.toStepOutcome(
                stepId = step.id,
                startTimestamp = stepStartTime,
                endTimestamp = Clock.System.now(),
            )
            stepOutcomes.add(stepOutcome)
            publishStepCompleted(plan, step, index, totalSteps, stepOutcome)

            // AMPR-408: the next step's executor — and so the parameter prompt of
            // whatever tool it nominates — sees what this one produced.
            context.addResult(stepOutcome)

            // Update context with any values from this step
            stepResult.contextUpdates.forEach { (key, value) ->
                context.set(key, value)
            }

            // Stop on a critical failure, or on a hold. A held step is waiting on a person,
            // so the steps after it would be handed an output that does not exist yet
            // (AMPR-425). The stop is here rather than in any one caller so that every
            // walk of a plan obeys it.
            val skipReason = when {
                stepOutcome is StepOutcome.Failure && stepOutcome.isCritical ->
                    "Skipped due to critical failure in step ${index + 1}"
                stepOutcome is StepOutcome.Held ->
                    "Skipped: step ${index + 1} is held (${stepOutcome.reason})"
                else -> null
            }
            if (skipReason != null) {
                // Mark remaining steps as skipped. A skip inherits the origin of the step
                // that caused it: a held reason is the tool's own text and is quoted into
                // the skip's (AMPR-453).
                for (remainingIndex in (offset + 1) until plan.tasks.size) {
                    val remainingStep = plan.tasks[remainingIndex]
                    stepOutcomes.add(
                        StepOutcome.Skipped(
                            id = remainingStep.id,
                            stepDescription = "Execute step ${remainingStep.id}",
                            timestamp = Clock.System.now(),
                            reason = skipReason,
                            origin = stepOutcome.origin,
                        ),
                    )
                }
                break
            }
        }

        // Build overall outcome
        val endTime = Clock.System.now()
        val folded = earlierStepOutcomes + stepOutcomes
        val summary = buildSummary(folded, context)
        val hasFailures = folded.any { it is StepOutcome.Failure }
        val heldStep = folded.firstNotNullOfOrNull { it as? StepOutcome.Held }
        // AMPR-453: the fold's text quotes its steps — the summary lists their errors and
        // held reasons, and a one-step plan's message becomes the step's own report — so it
        // is the run's own only when every step was.
        val origin = originOfAll(folded.map { it.origin })

        // A hold outranks an earlier non-critical failure: it is why the walk ended.
        val overallOutcome = when {
            heldStep != null -> ExecutionOutcome.Held(
                executorId = executorId,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                reason = heldStep.reason,
                reference = heldStep.reference,
                origin = origin,
            )
            hasFailures -> ExecutionOutcome.NoChanges.Failure(
                executorId = executorId,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = summary,
                origin = origin,
            )
            else -> ExecutionOutcome.NoChanges.Success(
                executorId = executorId,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = summary,
                origin = origin,
            )
        }

        return PlanExecutionResult(
            outcome = overallOutcome,
            stepOutcomes = stepOutcomes,
            context = context.toImmutable(),
        )
    }

    /**
     * The step is about to run. Published before the step executor is called, so a step
     * that never returns still shows up in the trace as started-and-unfinished rather
     * than not at all.
     */
    private suspend fun publishStepStarted(
        plan: Plan.ForTask,
        step: Task,
        index: Int,
        totalSteps: Int,
        startedAt: Instant,
    ) {
        val door = doorFor(step) ?: return
        door.publishPlanEvent(
            PlanEvent.PlanStepStarted(
                eventId = generateUUID("plan-step-started", plan.id, step.id),
                planId = plan.id,
                stepId = step.id,
                stepDescription = step.describe(),
                stepIndex = index,
                totalSteps = totalSteps,
                eventSource = EventSource.Agent(door.agentId),
                timestamp = startedAt,
                runId = runId,
                assignedTo = step.planStepSeat,
            ),
        )
    }

    /**
     * The step has run and its outcome is known. The description comes off the outcome
     * rather than the task: that is what the step executor reported having done, where
     * `Task.describe` is only what the plan asked for.
     *
     * Internal rather than private for the one step whose outcome is known without a walk:
     * a held step a hosted run resumes, whose result the consumer reported (AMPR-431). Its
     * `PlanStepStarted` went out before it held; this is the completion it was owed.
     */
    internal suspend fun publishStepCompleted(
        plan: Plan.ForTask,
        step: Task,
        index: Int,
        totalSteps: Int,
        outcome: StepOutcome,
    ) {
        val door = doorFor(step) ?: return
        door.publishPlanEvent(
            PlanEvent.PlanStepCompleted(
                eventId = generateUUID("plan-step-completed", plan.id, step.id),
                planId = plan.id,
                stepId = step.id,
                stepDescription = outcome.stepDescription,
                stepIndex = index,
                totalSteps = totalSteps,
                outcome = outcome,
                eventSource = EventSource.Agent(door.agentId),
                timestamp = Clock.System.now(),
                urgency = if (outcome is StepOutcome.Failure) Urgency.MEDIUM else Urgency.LOW,
                runId = runId,
                assignedTo = step.planStepSeat,
            ),
        )
    }

    /**
     * Under [NonCancellable] for the same reason as the provider-call telemetry: the step
     * has already run by the time the completion row is written, and a cancellation landing
     * here would lose the record of work that happened. It also keeps [execute]'s control
     * flow what it was — the loop catches a step's own cancellation and reports it as a
     * critical failure, and a publish that threw instead would turn that into a throw.
     */
    private suspend fun AgentEventApi.publishPlanEvent(event: PlanEvent) {
        withContext(NonCancellable) {
            publish(event, runId = this@PlanExecutor.runId)
        }
    }

    /**
     * Builds a summary message from step outcomes.
     *
     * Every failure's own error is listed, not just how many there were (AMPR-405).
     * This string becomes the `message` of the [ExecutionOutcome.NoChanges.Failure]
     * the plan settles to, and it is the only thing a caller holding that outcome has
     * to go on — a count told it that something failed and nothing about what, so a
     * whole plan that could not run reported the same `✗ Failure: 3` whether the tools
     * errored, the permissions were denied, or the agent had no executor at all.
     */
    private fun buildSummary(
        stepOutcomes: List<StepOutcome>,
        context: MutableStepContext,
    ): String = buildString {
        val successCount = stepOutcomes.count { it is StepOutcome.Success }
        val partialCount = stepOutcomes.count { it is StepOutcome.PartialSuccess }
        val failures = stepOutcomes.filterIsInstance<StepOutcome.Failure>()
        val held = stepOutcomes.filterIsInstance<StepOutcome.Held>()
        val skippedCount = stepOutcomes.count { it is StepOutcome.Skipped }

        appendLine("Plan execution complete:")
        appendLine("  ✓ Success: $successCount")
        if (partialCount > 0) {
            appendLine("  ⚠ Partial: $partialCount")
        }
        if (failures.isNotEmpty()) {
            appendLine("  ✗ Failure: ${failures.size}")
            failures.forEach { failure ->
                appendLine("      ${failure.stepDescription}: ${failure.error}")
            }
        }
        held.forEach { step ->
            appendLine("  ⏸ Held: ${step.stepDescription}: ${step.reason}")
        }
        if (skippedCount > 0) {
            appendLine("  ⊘ Skipped: $skippedCount")
        }

        // Add context-specific summaries
        context.getAll().forEach { (key, value) ->
            when {
                key.startsWith("created_") && value is Map<*, *> -> {
                    appendLine()
                    appendLine("Created ${value.size} items")
                }
            }
        }
    }

    companion object {
        /**
         * The executor a plan's summary outcome names (AMPR-443): the walk itself. The same
         * shape as an Arc run's own outcome, which names the Arc rather than any one agent.
         */
        const val PLAN_EXECUTOR_ID: ExecutorId = "plan"
    }
}

/**
 * The plan [PlanExecutor] is walking, in the coroutine context of each step it hands its
 * step executor (AMPR-472).
 *
 * It is how `dispatchPlanStep` names the plan on the `PlanEvent.StepAdmitted` it publishes
 * before a step runs. The step executor is the caller's lambda, and both drivers' lambdas
 * hand the dispatcher the step and the results before it, never the plan; carrying the id
 * here, the way a hosted run is carried as a context element, keeps it out of every
 * signature in between. Read with `currentCoroutineContext()[PlanWalk]`. A step dispatched
 * outside a walk has none, and publishes no admission, the same way it has no
 * `PlanStepStarted` / `PlanStepCompleted` pair for one to sit between.
 */
internal class PlanWalk(val planId: PlanId) : AbstractCoroutineContextElement(PlanWalk) {
    companion object Key : CoroutineContext.Key<PlanWalk>
}

/**
 * What the plan asked this step to do. Only the two plan-step types carry a
 * description; anything else is named by its id, matching the fallback
 * [PlanExecutor] already used when turning a thrown step into a
 * [StepResult.Failure].
 */
private fun Task.describe(): String = planStepDescription ?: "Execute step $id"

/**
 * Result of executing a single step.
 *
 * Every variant carries the [origin] of its text (AMPR-453), [ContentOrigin.External]
 * unless the step executor knows better — `SparkBasedAgent.executePlanStep` sets it from
 * the tool it ran, or from a reasoning step's inputs — and [toStepOutcome] carries it
 * onto the [StepOutcome] every later prompt labels.
 */
sealed class StepResult {
    abstract val description: String
    abstract val contextUpdates: Map<String, Any>

    /** Where this step's text came from. See [ContentOrigin]. */
    abstract val origin: ContentOrigin

    data class Success(
        override val description: String,
        val details: String? = null,
        override val contextUpdates: Map<String, Any> = emptyMap(),
        override val origin: ContentOrigin = ContentOrigin.External,
    ) : StepResult()

    data class PartialSuccess(
        override val description: String,
        val successCount: Int,
        val failureCount: Int,
        val details: String? = null,
        override val contextUpdates: Map<String, Any> = emptyMap(),
        override val origin: ContentOrigin = ContentOrigin.External,
    ) : StepResult()

    /**
     * @property tripped The rule that stopped the step before it ran (AMPR-472), carried
     *   onto [StepOutcome.Failure.tripped]. Null for every failure a rule did not cause.
     */
    data class Failure(
        override val description: String,
        val error: String,
        val isCritical: Boolean = false,
        override val contextUpdates: Map<String, Any> = emptyMap(),
        override val origin: ContentOrigin = ContentOrigin.External,
        val tripped: TrippedRule? = null,
    ) : StepResult()

    data class Skipped(
        override val description: String,
        val reason: String,
        override val contextUpdates: Map<String, Any> = emptyMap(),
        override val origin: ContentOrigin = ContentOrigin.External,
    ) : StepResult()

    /**
     * The step's tool is waiting on a person (AMPR-425). Neither a success nor a failure;
     * [PlanExecutor] stops the walk on it.
     *
     * @property reason What is being waited on, for a person to read
     * @property reference The tool's opaque handle for the hold, carried and never read
     */
    data class Held(
        override val description: String,
        val reason: String,
        val reference: String? = null,
        override val contextUpdates: Map<String, Any> = emptyMap(),
        override val origin: ContentOrigin = ContentOrigin.External,
    ) : StepResult()

    /** This result, with its text attributed to [origin] (AMPR-453). */
    fun withOrigin(origin: ContentOrigin): StepResult = when (this) {
        is Success -> copy(origin = origin)
        is PartialSuccess -> copy(origin = origin)
        is Failure -> copy(origin = origin)
        is Skipped -> copy(origin = origin)
        is Held -> copy(origin = origin)
    }

    /**
     * Converts this result to a StepOutcome with timestamps.
     */
    fun toStepOutcome(
        stepId: String,
        startTimestamp: kotlinx.datetime.Instant,
        endTimestamp: kotlinx.datetime.Instant,
    ): StepOutcome {
        return when (this) {
            is Success -> StepOutcome.Success(
                id = stepId,
                stepDescription = description,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                details = details ?: "",
                origin = origin,
            )
            is PartialSuccess -> StepOutcome.PartialSuccess(
                id = stepId,
                stepDescription = description,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                successCount = successCount,
                failureCount = failureCount,
                details = details ?: "",
                origin = origin,
            )
            is Failure -> StepOutcome.Failure(
                id = stepId,
                stepDescription = description,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                error = error,
                isCritical = isCritical,
                origin = origin,
                tripped = tripped,
            )
            is Skipped -> StepOutcome.Skipped(
                id = stepId,
                stepDescription = description,
                timestamp = startTimestamp,
                reason = reason,
                origin = origin,
            )
            is Held -> StepOutcome.Held(
                id = stepId,
                stepDescription = description,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                reason = reason,
                reference = reference,
                origin = origin,
            )
        }
    }

    companion object {
        fun success(description: String, details: String? = null) =
            Success(description, details)

        fun success(
            description: String,
            details: String? = null,
            contextUpdates: Map<String, Any>,
        ) = Success(description, details, contextUpdates)

        fun partial(
            description: String,
            successCount: Int,
            failureCount: Int,
            details: String? = null,
        ) = PartialSuccess(description, successCount, failureCount, details)

        fun failure(
            description: String,
            error: String,
            isCritical: Boolean = false,
            tripped: TrippedRule? = null,
        ) = Failure(description, error, isCritical, tripped = tripped)

        fun skip(description: String, reason: String) =
            Skipped(description, reason)

        fun held(description: String, reason: String, reference: String? = null) =
            Held(description, reason, reference)
    }
}

/**
 * Immutable context passed between steps.
 */
interface StepContext {
    fun <T> get(key: String): T?
    fun <T> getOrDefault(key: String, default: T): T
    fun contains(key: String): Boolean

    /**
     * What the steps before this one produced, oldest first (AMPR-408) — seeded with
     * whatever the caller had already run, then one entry appended per step this plan
     * executes.
     *
     * This is the typed channel for passing results forward, as against
     * [get]/[set][MutableStepContext.set], which carry only what a step chose to name
     * in `StepResult.contextUpdates`. A step executor that ignores it cannot dispatch
     * a step that depends on an earlier one: the parameter prompt of the tool it
     * nominates is built from the request it hands over, and nothing else carries the
     * chain.
     */
    val priorResults: List<StepOutcome>
}

/**
 * Mutable context that accumulates data during plan execution.
 */
class MutableStepContext(priorResults: List<StepOutcome> = emptyList()) : StepContext {
    private val data = mutableMapOf<String, Any>()
    private val results = priorResults.toMutableList()

    fun set(key: String, value: Any) {
        data[key] = value
    }

    /** Appends the outcome of a step that has just run to the running chain. */
    fun addResult(result: StepOutcome) {
        results += result
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String): T? = data[key] as? T

    override fun <T> getOrDefault(key: String, default: T): T = get(key) ?: default

    override fun contains(key: String): Boolean = data.containsKey(key)

    override val priorResults: List<StepOutcome>
        get() = results.toList()

    fun getAll(): Map<String, Any> = data.toMap()

    fun toImmutable(): StepContext = ImmutableStepContext(data.toMap(), results.toList())
}

/**
 * Immutable snapshot of step context.
 */
private class ImmutableStepContext(
    private val data: Map<String, Any>,
    override val priorResults: List<StepOutcome>,
) : StepContext {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String): T? = data[key] as? T

    override fun <T> getOrDefault(key: String, default: T): T = get(key) ?: default

    override fun contains(key: String): Boolean = data.containsKey(key)
}

/**
 * Result of executing an entire plan.
 */
data class PlanExecutionResult(
    val outcome: Outcome,
    val stepOutcomes: List<StepOutcome>,
    val context: StepContext,
) {
    val isSuccess: Boolean
        get() = outcome is Outcome.Success

    val hasFailures: Boolean
        get() = stepOutcomes.any { it is StepOutcome.Failure }

    val completedSteps: Int
        get() = stepOutcomes.count { it is StepOutcome.Success || it is StepOutcome.PartialSuccess }

    val totalSteps: Int
        get() = stepOutcomes.size
}
