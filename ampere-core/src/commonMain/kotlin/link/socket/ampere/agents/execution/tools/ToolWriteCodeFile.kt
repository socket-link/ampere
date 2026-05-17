package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeParams
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

expect suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged

const val WRITE_CODE_FILE_TOOL_ID: String = "write_code_file"

/**
 * Creates a FunctionTool that writes code files to the workspace.
 *
 * The tool ships with [CodeParams.CodeWriting] as its
 * [ParameterStrategy] by default so the sub-prompt that converts a
 * high-level intent into "files to write" lives with the tool rather
 * than being externally registered by every agent that wants the tool.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to
 *   use this tool.
 * @param parameterStrategy Override the default code-writing strategy
 *   (e.g. for testing). Pass `null` to disable param generation.
 */
fun ToolWriteCodeFile(
    requiredAgentAutonomy: AgentActionAutonomy,
    parameterStrategy: ParameterStrategy? = CodeParams.CodeWriting(),
): FunctionTool<ExecutionContext.Code.WriteCode> {
    return FunctionTool(
        id = WRITE_CODE_FILE_TOOL_ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeWriteCodeFile(executionRequest.context)
        },
        parameterStrategy = parameterStrategy,
    )
}

private const val NAME = "Write Code File"
private const val DESCRIPTION = "Writes a code file in the current workspace."
