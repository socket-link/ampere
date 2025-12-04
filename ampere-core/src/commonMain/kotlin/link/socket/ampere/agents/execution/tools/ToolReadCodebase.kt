package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

expect suspend fun executeReadCodebase(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading

/**
 * Creates a FunctionTool that reads code files from the workspace.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 * @return A FunctionTool configured to read code files.
 */
fun ToolReadCodebase(
    requiredAgentAutonomy: AgentActionAutonomy,
): FunctionTool<ExecutionContext.Code.ReadCode> {
    return FunctionTool(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeReadCodebase(executionRequest.context)
        }
    )
}

private const val ID = "read_codebase"
private const val NAME = "Read Codebase"
private const val DESCRIPTION = "Reads the codebase of the current workspace."
