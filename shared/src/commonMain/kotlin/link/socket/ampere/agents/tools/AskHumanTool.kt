package link.socket.ampere.agents.tools

import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

expect suspend fun executeAskHuman(
    context: ExecutionContext.NoChanges,
): ExecutionOutcome.NoChanges

data class AskHumanTool(
    override val requiredAgentAutonomy: AgentActionAutonomy,
) : Tool<ExecutionContext.NoChanges> {

    override val id: ToolId = ID
    override val name: String = NAME
    override val description: String = DESCRIPTION

    override suspend fun execute(
        executionRequest: ExecutionRequest<ExecutionContext.NoChanges>,
    ): Outcome {
        // TODO: Handle execution request constraints
        return executeAskHuman(executionRequest.context)
    }

    companion object {
        const val ID = "ask_human"
        const val NAME = "Ask a Human"
        const val DESCRIPTION = "Escalates uncertainty to human for guidance."
    }
}
