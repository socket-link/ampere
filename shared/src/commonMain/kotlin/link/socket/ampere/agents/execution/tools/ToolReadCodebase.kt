package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

expect suspend fun executeReadCodebase(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading

data class ToolReadCodebase(
    override val requiredAgentAutonomy: AgentActionAutonomy,
) : Tool<ExecutionContext.Code.ReadCode> {

    override val id: ToolId = ID
    override val name: String = NAME
    override val description: String = DESCRIPTION

    override suspend fun execute(
        executionRequest: ExecutionRequest<ExecutionContext.Code.ReadCode>,
    ): Outcome {
        // TODO: Handle execution request constraints
        return executeReadCodebase(executionRequest.context)
    }

    companion object Companion {
        const val ID = "read_codebase"
        const val NAME = "Read Codebase"
        const val DESCRIPTION = "Reads the codebase of the current workspace."
    }
}
