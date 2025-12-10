package link.socket.ampere.agents.definition

import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

interface NeuralAgent <S : AgentState> : Agent<S> {

    val runLLMToEvaluatePerception: (perception: Perception<S>) -> Idea
    val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan
    val runLLMToExecuteTask: (task: Task) -> Outcome
    val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome
    val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea

    fun callLLM(prompt: String): String {
        throw NotImplementedError("callLLM must be implemented by the agent")
    }
}
