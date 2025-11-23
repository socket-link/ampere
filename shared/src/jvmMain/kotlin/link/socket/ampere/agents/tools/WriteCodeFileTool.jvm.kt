package link.socket.ampere.agents.tools

import java.io.File
import link.socket.ampere.agents.core.AutonomyLevel
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.events.tasks.Task

/**
 * Tool that writes a single code file to a base directory with provided content.
 *
 * This JVM-specific implementation uses standard File I/O. It creates parent
 * directories as needed and returns an [Outcome] describing the result.
 */
actual class WriteCodeFileTool actual constructor(
    private val baseDirectory: String,
) : Tool {
    actual override val id: ToolId = "write_code_file"
    actual override val name: String = "Create Code"
    actual override val description: String = "Generates a single code file with specified content"
    actual override val requiredAutonomyLevel: AutonomyLevel = AutonomyLevel.ACT_WITH_NOTIFICATION

    actual override suspend fun execute(
        sourceTask: Task,
        parameters: Map<String, Any?>,
    ): Outcome {
        val filePath = parameters["filePath"]
            as? String
            ?: return Outcome.Failure(sourceTask, "Missing 'filePath' parameter")

        val content = parameters["content"]
            as? String
            ?: return Outcome.Failure(sourceTask, "Missing 'content' parameter")

        return try {
            val file = File(baseDirectory, filePath)

            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            file.writeText(content)

            Outcome.Success.Full(sourceTask, "File written: ${file.absolutePath}")
        } catch (e: Exception) {
            Outcome.Failure(sourceTask, "Failed to write file: ${e.message}")
        }
    }

    actual override fun validateParameters(parameters: Map<String, Any>): Boolean =
        parameters.containsKey("filePath") &&
            parameters.containsKey("content") &&
            parameters["filePath"] is String &&
            parameters["content"] is String
}
