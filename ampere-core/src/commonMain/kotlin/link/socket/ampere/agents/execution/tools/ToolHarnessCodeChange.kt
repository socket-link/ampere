package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

/** The id a plan step nominates to have a coding harness make a change (AMPR-492). */
const val HARNESS_CODE_CHANGE_TOOL_ID: ToolId = "harness_code_change"

/**
 * The tool a planner nominates to delegate a code change to an external coding harness
 * (AMPR-444, D6).
 *
 * The tool itself does nothing: a `CodeExecutor` bound for its id — an `AcpCodeExecutor`
 * behind a `RoutedExecutor` — performs the step, reading the step's description, the
 * workspace, the file access scope and the earlier steps' results off the request the engine
 * dispatches. There is deliberately no parameter strategy and no argument schema, so the
 * engine makes no model call before dispatch: the harness is the one that decides which files
 * to touch.
 *
 * Dispatched by anything else — a plain `FunctionExecutor`, which runs a tool's own function —
 * it fails with `TOOL_UNAVAILABLE` saying no code executor is bound for it. That is what a
 * consumer sees when they declare the tool on a seat without binding a harness.
 */
fun ToolHarnessCodeChange(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
    id = HARNESS_CODE_CHANGE_TOOL_ID,
    name = NAME,
    description = DESCRIPTION,
    requiredAgentAutonomy = requiredAgentAutonomy,
    executionFunction = { request -> unboundFailure(request) },
)

private fun unboundFailure(
    request: ExecutionRequest<ExecutionContext.NoChanges>,
): ExecutionOutcome.CodeChanged.Failure {
    val now = Clock.System.now()
    return ExecutionOutcome.CodeChanged.Failure(
        executorId = request.context.executorId,
        ticketId = request.context.ticket.id,
        taskId = request.context.task.id,
        executionStartTimestamp = now,
        executionEndTimestamp = now,
        partiallyChangedFiles = emptyList(),
        error = ExecutionError(
            type = ExecutionError.Type.TOOL_UNAVAILABLE,
            message = "No code executor is bound for $HARNESS_CODE_CHANGE_TOOL_ID: " +
                "bind a harness (an AcpCodeExecutor) to this tool id through a RoutedExecutor.",
            isRetryable = false,
        ),
    )
}

private const val NAME = "Code Change (harness)"
private const val DESCRIPTION =
    "Implements a code change in the current workspace through the bound coding harness. " +
        "Describe the change to make; the harness decides which files to edit."
