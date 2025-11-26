package link.socket.ampere.agents.tools

import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

expect suspend fun executeRunTests(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading

data class RunTestsTool(
    override val requiredAgentAutonomy: AgentActionAutonomy,
) : Tool<ExecutionContext.Code.ReadCode> {

    override val id: ToolId = ID
    override val name: String = NAME
    override val description: String = DESCRIPTION

    override suspend fun execute(
        executionRequest: ExecutionRequest<ExecutionContext.Code.ReadCode>,
    ): Outcome {
        // TODO: Handle execution request constraints
        return executeRunTests(executionRequest.context)
    }

    companion object {
        const val ID = "run_tests"
        const val NAME = "Run Tests"
        const val DESCRIPTION = "Runs tests in the current workspace."
    }
}
