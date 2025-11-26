package link.socket.ampere.agents.implementations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AutonomousAgent
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.tools.Tool
import link.socket.ampere.agents.tools.WriteCodeFileTool

/**
 * First concrete agent that can read tickets, generate code, and validate results (scaffold).
 *
 * This implementation intentionally keeps logic simple and deterministic to
 * satisfy the initial milestone requirements. It is multiplatform-friendly as
 * it only relies on commonMain types and contracts.
 */
class CodeWriterAgent(
    override val initialState: AgentState,
    override val agentConfiguration: AgentConfiguration,
    private val writeCodeFileTool: WriteCodeFileTool,
    private val coroutineScope: CoroutineScope,
) : AutonomousAgent<AgentState>() {

    override val id: AgentId = "CodeWriterAgent"

    override val requiredTools: Set<Tool<*>> =
        setOf(writeCodeFileTool)

    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
        { perception ->
            // TODO: evaluate perception of state with AI model
            Idea.blank
        }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas ->
            // TODO: plan out actions to take given ideas
            Plan.blank
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            // TODO: execute task
            Outcome.blank
        }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            // TODO: execute tool with parameters
            ExecutionOutcome.Blank
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            // TODO: evaluate outcomes and generate the idea to start the next runtime loop
            Idea.blank
        }

    private fun writeCodeFile(
        executionRequest: ExecutionRequest<ExecutionContext.Code.WriteCode>,
        onCodeSubmittedOutcome: (Outcome) -> Unit,
    ) {
        coroutineScope.launch {
            val outcome = writeCodeFileTool.execute(executionRequest)
            onCodeSubmittedOutcome(outcome)
        }
    }
}
