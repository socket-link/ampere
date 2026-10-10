package link.socket.ampere.agents.definition

import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

interface NeuralAgent<S : AgentState> : Agent<S> {

    val runLLMToEvaluatePerception: (perception: Perception<S>) -> Idea

    /**
     * Generates a plan for a task, informed by what Recall retrieved for it.
     *
     * `relevantKnowledge` is not optional (AMPR-388): a Plan generated without it
     * violates the *Recall precedes Plan* invariant of the PROPEL loop, so a caller
     * with nothing to supply passes `emptyList()` and says so, rather than letting
     * a default drop what Recall found.
     */
    val runLLMToPlan: (task: Task, ideas: List<Idea>, relevantKnowledge: List<KnowledgeWithScore>) -> Plan
    val runLLMToExecuteTask: (task: Task) -> Outcome
    val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome
    val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea

    fun callLLM(prompt: String): String {
        throw NotImplementedError("callLLM must be implemented by the agent")
    }
}
