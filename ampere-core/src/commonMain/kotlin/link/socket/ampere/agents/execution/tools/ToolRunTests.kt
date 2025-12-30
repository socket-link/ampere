package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

expect suspend fun executeRunTests(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading

/**
 * Creates a FunctionTool that runs tests in the workspace.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 * @return A FunctionTool configured to run tests.
 */
fun ToolRunTests(
    requiredAgentAutonomy: AgentActionAutonomy,
): FunctionTool<ExecutionContext.Code.ReadCode> {
    return FunctionTool(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeRunTests(executionRequest.context)
        },
    )
}

private const val ID = "run_tests"
private const val NAME = "Run Tests"
private const val DESCRIPTION = "Runs tests in the current workspace."
