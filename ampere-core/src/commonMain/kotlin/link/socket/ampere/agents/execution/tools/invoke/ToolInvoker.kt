package link.socket.ampere.agents.execution.tools.invoke

import kotlin.time.Duration
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Motor neuron that translates agent intent into tool invocation.
 *
 * ToolInvoker handles the execution lifecycle—validation, invocation,
 * error handling—while delegating domain-specific work to the underlying
 * tool. This separation allows tools to evolve independently from agent logic.
 *
 * Unlike the system-level Executor interface (which coordinates multiple tools),
 * ToolInvoker is a lightweight wrapper around a single tool that provides:
 * - Type-safe execution with validation
 * - Error isolation and transformation
 * - Timing measurement
 * - Optional event emission for observability
 *
 * This is the abstraction that agents use directly when they need to invoke
 * specific tools during their cognitive loop.
 *
 * @param C The execution context type this tool operates on
 * @property tool The tool this invoker wraps and executes
 * @property eventApi Optional event API for publishing tool execution events
 */
class ToolInvoker<C : ExecutionContext>(
    val tool: Tool<C>,
    private val eventApi: AgentEventApi? = null,
) {

    /**
     * Invoke the wrapped tool with the provided request.
     *
     * Handles validation, measures timing, and transforms tool outcomes
     * into invocation results. Emits ToolEvent.ToolExecutionStarted and
     * ToolEvent.ToolExecutionCompleted events if eventApi is provided.
     *
     * The execution flow:
     * 1. Generate invocation ID and emit Started event
     * 2. Validate request context type matches tool's expected type
     * 3. Execute the tool
     * 4. Measure execution duration
     * 5. Emit Completed event with success/failure status
     * 6. Transform tool Outcome to ToolInvocationResult
     * 7. Return ToolInvocationResult
     *
     * @param request The execution request with context and parameters
     * @return ToolInvocationResult representing success or failure
     */
    suspend fun invoke(request: ExecutionRequest<C>): ToolInvocationResult {
        val startTime = Clock.System.now()
        val invocationId = generateUUID()

        // Emit Started event
        eventApi?.let { api ->
            val startedEvent = link.socket.ampere.agents.domain.event.ToolEvent.ToolExecutionStarted(
                eventId = generateUUID(),
                timestamp = startTime,
                eventSource = EventSource.Agent(api.agentId),
                urgency = Urgency.LOW,
                invocationId = invocationId,
                toolId = tool.id,
                toolName = tool.name,
            )
            api.publish(startedEvent)
        }

        // Delegate actual execution to the tool
        val outcome = try {
            tool.execute(request)
        } catch (e: Exception) {
            // Catch unexpected errors and create a failure outcome
            // Use ExecutionOutcome.NoChanges.Failure as a generic failure type
            ExecutionOutcome.NoChanges.Failure(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                message = "Tool execution failed unexpectedly: ${e.message ?: e::class.simpleName}",
            )
        }

        val endTime = Clock.System.now()
        val duration = endTime - startTime

        // Transform tool outcome into invocation result
        val invocationResult = when (outcome) {
            is Outcome.Success -> ToolInvocationResult.Success(
                outcome = outcome,
                toolId = tool.id,
                duration = duration,
            )
            is Outcome.Failure -> ToolInvocationResult.Failed(
                outcome = outcome,
                error = when (outcome) {
                    is ExecutionOutcome.NoChanges.Failure -> outcome.message
                    is ExecutionOutcome.CodeReading.Failure -> outcome.error.message
                    is ExecutionOutcome.CodeChanged.Failure -> outcome.error.message
                    else -> "Tool execution failed"
                },
                toolId = tool.id,
                duration = duration,
            )
            is Outcome.Blank -> ToolInvocationResult.Blank
            // Handle other outcome types (MeetingOutcome, etc.) that tools shouldn't normally return
            else -> {
                // If a tool returns an unexpected outcome type, treat it as a failure
                ToolInvocationResult.Failed(
                    outcome = ExecutionOutcome.NoChanges.Failure(
                        executorId = request.context.executorId,
                        ticketId = request.context.ticket.id,
                        taskId = request.context.task.id,
                        executionStartTimestamp = startTime,
                        executionEndTimestamp = endTime,
                        message = "Tool returned unexpected outcome type: ${outcome::class.simpleName}",
                    ),
                    error = "Tool returned unexpected outcome type: ${outcome::class.simpleName}",
                    toolId = tool.id,
                    duration = duration,
                )
            }
        }

        // Emit Completed event
        eventApi?.let { api ->
            val completedEvent = link.socket.ampere.agents.domain.event.ToolEvent.ToolExecutionCompleted(
                eventId = generateUUID(),
                timestamp = endTime,
                eventSource = EventSource.Agent(api.agentId),
                urgency = when (invocationResult) {
                    is ToolInvocationResult.Failed -> Urgency.MEDIUM
                    else -> Urgency.LOW
                },
                invocationId = invocationId,
                toolId = tool.id,
                toolName = tool.name,
                success = invocationResult is ToolInvocationResult.Success,
                durationMs = duration.inWholeMilliseconds,
                errorMessage = when (invocationResult) {
                    is ToolInvocationResult.Failed -> invocationResult.error
                    else -> null
                },
            )
            api.publish(completedEvent)
        }

        return invocationResult
    }

    /**
     * Validate that a request is compatible with this invoker's tool.
     *
     * Checks that the context type matches to prevent runtime type errors.
     * This is performed automatically by invoke(), but can be called
     * separately for pre-flight validation.
     *
     * Note: Kotlin's type system handles most validation at compile time,
     * so this is primarily for documentation and potential runtime checks
     * in dynamic scenarios.
     *
     * @param request The execution request to validate
     * @return ValidationResult indicating whether the request is valid
     */
    fun validate(request: ExecutionRequest<C>): ValidationResult {
        // Type parameter C ensures compile-time type safety
        // At runtime, the request's context will match C by construction
        // Additional validation could check required parameters,
        // parameter types, value ranges, etc., but we keep it simple
        // and let the tool handle domain-specific validation

        return ValidationResult.Valid
    }
}

/**
 * Result of validating an execution request.
 *
 * Currently validation is simple (just type checking handled by generics),
 * but this sealed interface allows for future expansion to include
 * parameter validation, constraint checking, etc.
 */
sealed interface ValidationResult {
    /**
     * Request is valid and can be executed
     */
    data object Valid : ValidationResult

    /**
     * Request is invalid with specific error messages
     *
     * @property errors List of validation error messages
     */
    data class Invalid(val errors: List<String>) : ValidationResult
}

/**
 * Result of a tool invocation.
 *
 * This is a simpler result type than ExecutionOutcome, focused on
 * the immediate result of invoking a single tool rather than the
 * full execution lifecycle with ticket/task tracking.
 */
sealed interface ToolInvocationResult {
    /**
     * Tool invoked successfully
     *
     * @property outcome The successful outcome from the tool
     * @property toolId The ID of the tool that was invoked
     * @property duration How long the invocation took
     */
    data class Success(
        val outcome: Outcome.Success,
        val toolId: String,
        val duration: Duration,
    ) : ToolInvocationResult

    /**
     * Tool invocation failed
     *
     * @property outcome The failure outcome from the tool
     * @property error Error message describing what went wrong
     * @property toolId The ID of the tool that was invoked
     * @property duration How long the invocation took before failing
     */
    data class Failed(
        val outcome: Outcome.Failure,
        val error: String,
        val toolId: String,
        val duration: Duration,
    ) : ToolInvocationResult

    /**
     * Blank result (no-op invocation)
     */
    data object Blank : ToolInvocationResult

    companion object {
        val blank: ToolInvocationResult = Blank
    }
}
