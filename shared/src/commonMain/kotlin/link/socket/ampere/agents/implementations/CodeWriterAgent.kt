package link.socket.ampere.agents.implementations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.Idea
import link.socket.ampere.agents.core.MinimalAutonomousAgent
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.core.Perception
import link.socket.ampere.agents.core.Plan
import link.socket.ampere.agents.events.tasks.Task
import link.socket.ampere.agents.tools.Tool
import link.socket.ampere.agents.tools.WriteCodeFileTool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * First concrete agent that can read tickets, generate code, and validate results (scaffold).
 *
 * This implementation intentionally keeps logic simple and deterministic to
 * satisfy the initial milestone requirements. It is multiplatform-friendly as
 * it only relies on commonMain types and contracts.
 */
class CodeWriterAgent(
    private val coroutineScope: CoroutineScope,
    private val writeCodeFileTool: WriteCodeFileTool,
    runLLMToPerceive: (perception: Perception) -> Idea,
    runLLMToPlan: (ideas: List<Idea>) -> Plan,
    runLLMToExecuteTask: (task: Task) -> Outcome,
    runLLMToExecuteTool: (tool: Tool, parameters: Map<String, Any?>) -> Outcome,
    runLLMToEvaluate: (outcomes: List<Outcome>) -> Idea,
    aiConfiguration: AIConfiguration,
) : MinimalAutonomousAgent(
    runLLMToPerceive,
    runLLMToPlan,
    runLLMToExecuteTask,
    runLLMToExecuteTool,
    runLLMToEvaluate,
    AgentConfiguration(
        agentDefinition = WriteCodeAgent,
        aiConfiguration = aiConfiguration,
    ),
) {

    override val id: AgentId = "CodeWriterAgent"

    override val requiredTools: Set<Tool> =
        setOf(writeCodeFileTool)

    private fun writeCodeFile(
        sourceTask: Task,
        parameters: Map<String, Any?> = emptyMap(),
        onCodeSubmittedOutcome: (Outcome) -> Unit,
    ) {
        coroutineScope.launch {
            val outcome = writeCodeFileTool.execute(sourceTask, parameters)
            onCodeSubmittedOutcome(outcome)
        }
    }
}
