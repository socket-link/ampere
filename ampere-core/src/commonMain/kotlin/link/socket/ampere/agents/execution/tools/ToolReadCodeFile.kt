package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeParams
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

const val READ_CODE_FILE_TOOL_ID: String = "read_code_file"

/**
 * Creates a FunctionTool that reads code files from the workspace.
 *
 * Wraps the same platform-side `executeReadCodebase` implementation as
 * [ToolReadCodebase] but exposes the tool under the canonical
 * `read_code_file` id that the declarative role-code spark references, and
 * ships with the [CodeParams.CodeReading]
 * parameter strategy attached so an agent that wants the tool does not
 * need to register a strategy separately.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to
 *   use this tool.
 * @param parameterStrategy Override the default code-reading strategy
 *   (e.g. for testing). Pass `null` to disable param generation.
 */
fun ToolReadCodeFile(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    parameterStrategy: ParameterStrategy? = CodeParams.CodeReading(),
): FunctionTool<ExecutionContext.Code.ReadCode> {
    return FunctionTool(
        id = READ_CODE_FILE_TOOL_ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            executeReadCodebase(executionRequest.context)
        },
        parameterStrategy = parameterStrategy,
    )
}

private const val NAME = "Read Code File"
private const val DESCRIPTION = "Reads one or more code files from the current workspace."
