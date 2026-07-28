package link.socket.ampere.agents.execution.executor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.health.ExecutorSystemHealth

/**
 * Executor that performs no real work (AMPR-186).
 *
 * Every call is answered with a synthetic [ExecutionOutcome.NoChanges.Success] without
 * dispatching to the tool's actual side-effecting implementation — the tool's `execute`
 * function is never invoked. This is the effect-free seam the eval `Bench` harness injects
 * (via [link.socket.ampere.agents.definition.SparkAgentFactory]/`SparkBasedAgent`) so an
 * Arc run can drive real tool-selection logic while guaranteeing no notification, file
 * write, git operation, or other side effect actually happens during a bench run.
 */
class NoOpExecutor(
    override val id: ExecutorId = "noop",
    override val displayName: String = "No-Op Executor (effect-free Bench mode)",
    override val capabilities: ExecutorCapabilities = ExecutorCapabilities(
        supportsLanguages = emptySet(),
        supportsFrameworks = emptySet(),
    ),
) : Executor {

    override suspend fun performHealthCheck(): Result<ExecutorSystemHealth> =
        Result.success(
            ExecutorSystemHealth(
                version = null,
                isAvailable = true,
                issues = emptyList(),
            ),
        )

    override suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus> = flow {
        val now = Clock.System.now()
        emit(ExecutionStatus.Started(executorId = id, timestamp = now))
        emit(
            ExecutionStatus.Completed(
                executorId = id,
                timestamp = Clock.System.now(),
                result = ExecutionOutcome.NoChanges.Success(
                    executorId = id,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = Clock.System.now(),
                    message = "Skipped by NoOpExecutor: '${tool.name}' was not dispatched (effect-free mode).",
                ),
            ),
        )
    }
}
