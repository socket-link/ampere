package link.socket.kore.agents.tools

import java.io.File
import link.socket.kore.agents.core.AutonomyLevel
import link.socket.kore.agents.core.Outcome
import link.socket.kore.agents.events.tasks.Task

/**
 * Tool that reads file contents or lists a directory within a sandboxed root directory.
 * Prevents reading paths outside the configured [rootDirectory].
 */
actual class ReadCodebaseTool actual constructor(
    private val rootDirectory: String,
) : Tool {
    actual override val id: ToolId = "read_codebase"
    actual override val name: String = "View Code"
    actual override val description: String = "Reads file content or directory structure"
    actual override val requiredAutonomyLevel: AutonomyLevel = AutonomyLevel.FULLY_AUTONOMOUS

    private fun resolveSafe(path: String): File {
        val base = File(rootDirectory).canonicalFile
        val target = File(base, path).canonicalFile

        // Ensure target is within base directory
        if (!target.path.startsWith(base.path + File.separator) && target != base) {
            throw SecurityException("Access outside root directory is not allowed: $path")
        }
        return target
    }

    actual override suspend fun execute(
        sourceTask: Task,
        parameters: Map<String, Any?>,
    ): Outcome {
        val path = parameters["path"] as? String
            ?: return Outcome.Failure(sourceTask, "Missing 'path' parameter")

        return try {
            val file = resolveSafe(path)
            if (!file.exists()) {
                return Outcome.Failure(sourceTask, "Path does not exist: $path")
            }

            val result: Any = if (file.isDirectory) {
                file.listFiles()?.joinToString("\n") { it.name } ?: ""
            } else {
                file.readText()
            }
            Outcome.Success.Full(sourceTask, result.toString())
        } catch (e: SecurityException) {
            Outcome.Failure(sourceTask, e.message ?: "Access outside root directory is not allowed: $path")
        } catch (e: Exception) {
            Outcome.Failure(sourceTask, "Failed to read: ${e.message}")
        }
    }

    actual override fun validateParameters(parameters: Map<String, Any>): Boolean {
        return parameters.containsKey("path") && parameters["path"] is String
    }
}
