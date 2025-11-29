package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

expect suspend fun executeAskHuman(
    context: ExecutionContext.NoChanges,
): ExecutionOutcome.NoChanges

/**
 * Creates a FunctionTool that asks a human for guidance.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 * @return A FunctionTool configured to escalate to humans.
 */
fun ToolAskHuman(
    requiredAgentAutonomy: AgentActionAutonomy,
): FunctionTool<ExecutionContext.NoChanges> {
    return FunctionTool(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeAskHuman(executionRequest.context)
        }
    )
}

private const val ID = "ask_human"
private const val NAME = "Ask a Human"
private const val DESCRIPTION = "Escalates uncertainty to human for guidance."
