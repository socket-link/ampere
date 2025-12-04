package link.socket.ampere.agents.tools

import java.io.File
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Android actual for WriteCodeFileTool using java.io.File APIs.
 */
actual class WriteCodeFileTool actual constructor(
    private val baseDirectory: String
) : Tool {
    actual override val name: String = "write_code_file"
    actual override val description: String = "Generates a single code file with specified content"
    actual val agentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION

    actual override suspend fun execute(parameters: Map<String, Any?>): Outcome {
        val filePath = parameters["filePath"]
            as? String
            ?: return Outcome(
                success = false,
                result = null,
                errorMessage = "Missing 'filePath' parameter",
            )

        val content = parameters["content"]
            as? String
            ?: return Outcome(
                success = false,
                result = null,
                errorMessage = "Missing 'content' parameter",
            )

        return try {
            val file = File(baseDirectory, filePath)

            file.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            file.writeText(content)

            Outcome(
                success = true,
                result = $$"File written: ${file.absolutePath}",
            )
        } catch (e: Exception) {
            // TODO: Log exception
            Outcome(
                success = false,
                result = null,
                errorMessage = $$"Failed to write file: ${e.message}",
            )
        }
    }

    actual override fun validateParameters(parameters: Map<String, Any>): Boolean {
        return parameters.containsKey("filePath") &&
            parameters.containsKey("content") &&
            parameters["filePath"] is String &&
            parameters["content"] is String
    }
}
