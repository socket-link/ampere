package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

expect suspend fun executeAskHuman(
    context: ExecutionContext.NoChanges,
): ExecutionOutcome.NoChanges

const val ASK_HUMAN_TOOL_ID: String = "ask_human"

/**
 * Creates a FunctionTool that asks a human for guidance.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 * @param parameterStrategy Optional tool-owned strategy for converting an
 *   unstructured intent into the structured escalation parameters.
 * @return A FunctionTool configured to escalate to humans.
 */
fun ToolAskHuman(
    requiredAgentAutonomy: AgentActionAutonomy,
    parameterStrategy: ParameterStrategy? = null,
): FunctionTool<ExecutionContext.NoChanges> {
    return FunctionTool(
        id = ASK_HUMAN_TOOL_ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            executeAskHuman(executionRequest.context)
        },
        parameterStrategy = parameterStrategy,
    )
}
private const val NAME = "Ask a Human"
private const val DESCRIPTION = "Escalates uncertainty to human for guidance."
