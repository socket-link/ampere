package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

expect suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged

/**
 * Creates a FunctionTool that writes code files to the workspace.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 * @return A FunctionTool configured to write code files.
 */
fun ToolWriteCodeFile(
    requiredAgentAutonomy: AgentActionAutonomy,
): FunctionTool<ExecutionContext.Code.WriteCode> {
    return FunctionTool(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeWriteCodeFile(executionRequest.context)
        }
    )
}

private const val ID = "write_code_file"
private const val NAME = "Write Code File"
private const val DESCRIPTION = "Writes a code file in the current workspace."
