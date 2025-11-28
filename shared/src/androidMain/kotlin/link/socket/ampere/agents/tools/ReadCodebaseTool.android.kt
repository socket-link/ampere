package link.socket.ampere.agents.tools

import java.io.File
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Android actual for ReadCodebaseTool. Restricts reads to a sandboxed root.
 */
actual class ReadCodebaseTool actual constructor(
    private val rootDirectory: String,
) : Tool {
    actual override val name: String = "read_codebase"
    actual override val description: String = "Reads file content or directory structure"
    actual override val agentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS

    private fun resolveSafe(path: String): File {
        val base = File(rootDirectory).canonicalFile
        val target = File(base, path).canonicalFile
        if (!target.path.startsWith(base.path + File.separator) && target != base) {
            throw SecurityException("Access outside root directory is not allowed: $path")
        }
        return target
    }

    actual override suspend fun execute(parameters: Map<String, Any?>): Outcome {
        val path = parameters["path"] as? String
            ?: return Outcome(false, null, "Missing 'path' parameter")

        return try {
            val file = resolveSafe(path)
            if (!file.exists()) {
                return Outcome(false, null, "Path does not exist: $path")
            }
            val result: Any = if (file.isDirectory) {
                file.listFiles()?.joinToString("\n") { it.name } ?: ""
            } else {
                file.readText()
            }
            Outcome(true, result)
        } catch (e: SecurityException) {
            Outcome(false, null, e.message)
        } catch (e: Exception) {
            Outcome(false, null, "Failed to read: ${e.message}")
        }
    }

    actual override fun validateParameters(parameters: Map<String, Any>): Boolean {
        return parameters.containsKey("path") && parameters["path"] is String
    }
}
