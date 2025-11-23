package link.socket.ampere.agents.tools

import link.socket.ampere.agents.core.AutonomyLevel
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.events.tasks.Task

/**
 * A safety-valve tool that escalates uncertainty to a human operator.
 * The tool forwards a question to a provided [humanInterface] and returns the
 * response in an [Outcome].
 */
class AskHumanTool(
    private val humanInterface: (String) -> String,
) : Tool {
    override val id: ToolId = "ask_human"
    override val name: String = "Ask a Human"
    override val description: String = "Escalates uncertainty to human for guidance"
    override val requiredAutonomyLevel: AutonomyLevel = AutonomyLevel.ASK_BEFORE_ACTION

    override suspend fun execute(
        sourceTask: Task,
        parameters: Map<String, Any?>,
    ): Outcome {
        val question = parameters["question"] as? String
            ?: return Outcome.Failure(sourceTask, "Missing 'question' parameter")

        return try {
            val response = humanInterface(question)
            Outcome.Success.Full(sourceTask, response)
        } catch (e: Exception) {
            Outcome.Failure(sourceTask, "Failed to get human response: ${e.message}")
        }
    }

    override fun validateParameters(parameters: Map<String, Any>): Boolean {
        return parameters.containsKey("question") && parameters["question"] is String
    }
}
