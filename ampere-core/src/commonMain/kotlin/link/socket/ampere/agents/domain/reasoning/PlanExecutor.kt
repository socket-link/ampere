package link.socket.ampere.agents.domain.reasoning

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.task.Task
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
 * - Step outcome collection for analysis
 * - Context passing between steps (e.g., created issue IDs)
 *
 * Usage:
 * ```kotlin
 * val executor = PlanExecutor(executorId)
 * val result = executor.execute(plan) { step, context ->
 *     when (step) {
 *         is PMTask.CreateIssues -> executeCreateIssues(step, context)
 *         is PMTask.AssignTask -> executeAssignTask(step, context)
 *         else -> StepResult.skip("Unknown step type")
 *     }
 * }
 * ```
 *
 * @property executorId ID of the agent executing the plan
 * @property eventApi The agent's event door (AMPR-389). With one, each executed step
 *   leaves a [PlanEvent.PlanStepStarted] / [PlanEvent.PlanStepCompleted] pair under
 *   [runId], so an Arc run's trace shows the steps Execute actually ran. Null keeps the
 *   silence this class had before — a plan executed outside a run, or by a caller with
 *   no door, publishes nothing.
 * @property runId The Arc run the plan belongs to. Goes on both events and on their
 *   envelope, which is what `ArcTraceProjection` joins on.
 */
class PlanExecutor(
    private val executorId: ExecutorId,
    private val eventApi: AgentEventApi? = null,
    private val runId: RunId? = null,
) {

    /**
     * Executes a plan step by step.
     *
     * @param plan The plan to execute
     * @param stepExecutor Function to execute individual steps
     * @return PlanExecutionResult with overall outcome and step details
     */
    suspend fun execute(
        plan: Plan,
        stepExecutor: suspend (Task, StepContext) -> StepResult,
    ): PlanExecutionResult {
        val startTime = Clock.System.now()
        val stepOutcomes = mutableListOf<StepOutcome>()
        val context = MutableStepContext()

        // Handle empty or non-task plans
        if (plan !is Plan.ForTask || plan.tasks.isEmpty()) {
            val taskId = if (plan is Plan.ForTask) plan.task.id else ""
            return PlanExecutionResult(
                outcome = ExecutionOutcome.NoChanges.Success(
                    executorId = executorId,
                    ticketId = "",
                    taskId = taskId,
                    message = "Plan has no steps to execute",
                    executionStartTimestamp = startTime,
                    executionEndTimestamp = Clock.System.now(),
                ),
                stepOutcomes = emptyList(),
                context = context.toImmutable(),
            )
        }

        // Execute steps sequentially
        for ((index, step) in plan.tasks.withIndex()) {
            val stepStartTime = Clock.System.now()
            publishStepStarted(plan, step, index, stepStartTime)

            val stepResult = try {
                stepExecutor(step, context.toImmutable())
            } catch (e: Exception) {
                StepResult.failure(
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
            publishStepCompleted(plan, step, index, stepOutcome)

            // Update context with any values from this step
            stepResult.contextUpdates.forEach { (key, value) ->
                context.set(key, value)
            }

            // Stop on critical failure
            if (stepOutcome is StepOutcome.Failure && stepOutcome.isCritical) {
                // Mark remaining steps as skipped
                for (remainingIndex in (index + 1) until plan.tasks.size) {
                    val remainingStep = plan.tasks[remainingIndex]
                    stepOutcomes.add(
                        StepOutcome.Skipped(
                            id = remainingStep.id,
                            stepDescription = "Execute step ${remainingStep.id}",
                            timestamp = Clock.System.now(),
                            reason = "Skipped due to critical failure in step ${index + 1}",
                        ),
                    )
                }
                break
            }
        }

        // Build overall outcome
        val endTime = Clock.System.now()
        val summary = buildSummary(stepOutcomes, context)
        val hasFailures = stepOutcomes.any { it is StepOutcome.Failure }

        val overallOutcome = if (hasFailures) {
            ExecutionOutcome.NoChanges.Failure(
                executorId = executorId,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = summary,
            )
        } else {
            ExecutionOutcome.NoChanges.Success(
                executorId = executorId,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = summary,
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
        startedAt: Instant,
    ) {
        val door = eventApi ?: return
        door.publishPlanEvent(
            PlanEvent.PlanStepStarted(
                eventId = generateUUID("plan-step-started", plan.id, step.id),
                planId = plan.id,
                stepId = step.id,
                stepDescription = step.describe(),
                stepIndex = index,
                totalSteps = plan.tasks.size,
                eventSource = EventSource.Agent(door.agentId),
                timestamp = startedAt,
                runId = runId,
            ),
        )
    }

    /**
     * The step has run and its outcome is known. The description comes off the outcome
     * rather than the task: that is what the step executor reported having done, where
     * `Task.describe` is only what the plan asked for.
     */
    private suspend fun publishStepCompleted(
        plan: Plan.ForTask,
        step: Task,
        index: Int,
        outcome: StepOutcome,
    ) {
        val door = eventApi ?: return
        door.publishPlanEvent(
            PlanEvent.PlanStepCompleted(
                eventId = generateUUID("plan-step-completed", plan.id, step.id),
                planId = plan.id,
                stepId = step.id,
                stepDescription = outcome.stepDescription,
                stepIndex = index,
                totalSteps = plan.tasks.size,
                outcome = outcome,
                eventSource = EventSource.Agent(door.agentId),
                timestamp = Clock.System.now(),
                urgency = if (outcome is StepOutcome.Failure) Urgency.MEDIUM else Urgency.LOW,
                runId = runId,
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
     */
    private fun buildSummary(
        stepOutcomes: List<StepOutcome>,
        context: MutableStepContext,
    ): String = buildString {
        val successCount = stepOutcomes.count { it is StepOutcome.Success }
        val partialCount = stepOutcomes.count { it is StepOutcome.PartialSuccess }
        val failureCount = stepOutcomes.count { it is StepOutcome.Failure }
        val skippedCount = stepOutcomes.count { it is StepOutcome.Skipped }

        appendLine("Plan execution complete:")
        appendLine("  ✓ Success: $successCount")
        if (partialCount > 0) {
            appendLine("  ⚠ Partial: $partialCount")
        }
        if (failureCount > 0) {
            appendLine("  ✗ Failure: $failureCount")
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
}

/**
 * What the plan asked this step to do. Only [Task.CodeChange] carries a description;
 * anything else is named by its id, matching the fallback [PlanExecutor] already used
 * when turning a thrown step into a [StepResult.Failure].
 */
private fun Task.describe(): String = when (this) {
    is Task.CodeChange -> description
    else -> "Execute step $id"
}

/**
 * Result of executing a single step.
 */
sealed class StepResult {
    abstract val description: String
    abstract val contextUpdates: Map<String, Any>

    data class Success(
        override val description: String,
        val details: String? = null,
        override val contextUpdates: Map<String, Any> = emptyMap(),
    ) : StepResult()

    data class PartialSuccess(
        override val description: String,
        val successCount: Int,
        val failureCount: Int,
        val details: String? = null,
        override val contextUpdates: Map<String, Any> = emptyMap(),
    ) : StepResult()

    data class Failure(
        override val description: String,
        val error: String,
        val isCritical: Boolean = false,
        override val contextUpdates: Map<String, Any> = emptyMap(),
    ) : StepResult()

    data class Skipped(
        override val description: String,
        val reason: String,
        override val contextUpdates: Map<String, Any> = emptyMap(),
    ) : StepResult()

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
            )
            is PartialSuccess -> StepOutcome.PartialSuccess(
                id = stepId,
                stepDescription = description,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                successCount = successCount,
                failureCount = failureCount,
                details = details ?: "",
            )
            is Failure -> StepOutcome.Failure(
                id = stepId,
                stepDescription = description,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                error = error,
                isCritical = isCritical,
            )
            is Skipped -> StepOutcome.Skipped(
                id = stepId,
                stepDescription = description,
                timestamp = startTimestamp,
                reason = reason,
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

        fun failure(description: String, error: String, isCritical: Boolean = false) =
            Failure(description, error, isCritical)

        fun skip(description: String, reason: String) =
            Skipped(description, reason)
    }
}

/**
 * Immutable context passed between steps.
 */
interface StepContext {
    fun <T> get(key: String): T?
    fun <T> getOrDefault(key: String, default: T): T
    fun contains(key: String): Boolean
}

/**
 * Mutable context that accumulates data during plan execution.
 */
class MutableStepContext : StepContext {
    private val data = mutableMapOf<String, Any>()

    fun set(key: String, value: Any) {
        data[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String): T? = data[key] as? T

    override fun <T> getOrDefault(key: String, default: T): T = get(key) ?: default

    override fun contains(key: String): Boolean = data.containsKey(key)

    fun getAll(): Map<String, Any> = data.toMap()

    fun toImmutable(): StepContext = ImmutableStepContext(data.toMap())
}

/**
 * Immutable snapshot of step context.
 */
private class ImmutableStepContext(private val data: Map<String, Any>) : StepContext {
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
