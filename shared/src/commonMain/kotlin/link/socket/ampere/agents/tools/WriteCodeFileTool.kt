package link.socket.ampere.agents.tools

import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

expect suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged

data class WriteCodeFileTool(
    override val requiredAgentAutonomy: AgentActionAutonomy,
) : Tool<ExecutionContext.Code.WriteCode> {

    override val id: ToolId = ID
    override val name: String = NAME
    override val description: String = DESCRIPTION

    override suspend fun execute(
        executionRequest: ExecutionRequest<ExecutionContext.Code.WriteCode>,
    ): Outcome {
        // TODO: Handle execution request constraints
        return executeWriteCodeFile(executionRequest.context)
    }

    companion object {
        const val ID = "write_code_file"
        const val NAME = "Write Code File"
        const val DESCRIPTION = "Writes a code file in the current workspace."
    }
}
