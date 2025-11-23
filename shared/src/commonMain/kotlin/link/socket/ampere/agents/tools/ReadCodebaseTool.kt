package link.socket.ampere.agents.tools

import link.socket.ampere.agents.core.AutonomyLevel
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.events.tasks.Task

/**
 * Expect declaration for a tool that reads file contents or directory structure
 * within a sandboxed root directory.
 */
expect class ReadCodebaseTool(rootDirectory: String) : Tool {
    override val id: ToolId
    override val name: String
    override val description: String
    override val requiredAutonomyLevel: AutonomyLevel
    override suspend fun execute(
        sourceTask: Task,
        parameters: Map<String, Any?>,
    ): Outcome
    override fun validateParameters(parameters: Map<String, Any>): Boolean
}
