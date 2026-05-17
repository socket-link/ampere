package link.socket.ampere.agents.execution.tools.git

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Parameter strategies for git tools.
 *
 * Each git tool that the planner can nominate via `toolToUse` ships with the
 * strategy that converts the plan step's intent into the appropriate
 * [GitOperationRequest] sub-record. Strategies are deliberately small —
 * they translate the step's intent string (and any context already on the
 * request) into structured parameters; they do not contain any
 * agent-specific routing or fallback logic.
 */
sealed class GitParams {

    /**
     * Strategy for the `git_commit` tool. Asks the LLM to produce a commit
     * message and an optional file list / issue-number reference from the
     * plan step's intent and current workspace state.
     */
    class Commit(
        private val repositoryHint: String = ".",
    ) : ParameterStrategy {

        override val systemMessage: String =
            "You are a git commit message author. Produce a concise, " +
                "imperative-mood commit message and identify which files " +
                "(and optionally which issue number) should accompany the " +
                "commit. Respond only with valid JSON."

        override val maxTokens: Int = 600

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String {
            val existingFiles = (request.context as? ExecutionContext.GitOperation)
                ?.gitRequest?.stageFiles ?: emptyList()

            return buildString {
                appendLine("You are preparing a git commit for the following intent:")
                appendLine()
                appendLine(intent)
                appendLine()
                if (existingFiles.isNotEmpty()) {
                    appendLine("Files already staged in this step's context:")
                    existingFiles.forEach { appendLine("- $it") }
                    appendLine()
                }
                appendLine("Respond with a JSON object of exactly this shape:")
                appendLine(
                    """
{
  "message": "<imperative commit message, ≤72 chars on the first line>",
  "files": ["path/relative/to/repo", "..."],
  "issueNumber": <integer or null>
}
                    """.trimIndent(),
                )
                appendLine()
                appendLine("- 'message' is required and must not be empty.")
                appendLine("- 'files' may be empty when committing what's already staged.")
                appendLine("- 'issueNumber' may be null when no issue is being closed.")
                appendLine()
                appendLine("Respond ONLY with the JSON object, no other text.")
            }
        }

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> {
            val cleaned = jsonResponse
                .trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val obj = json.parseToJsonElement(cleaned).jsonObject
            val message = obj["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("git_commit strategy: response missing non-empty 'message'")
            val files = obj["files"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
            val issueNumber = obj["issueNumber"]?.jsonPrimitive?.intOrNull

            val originalContext = originalRequest.context
            val priorRepo = (originalContext as? ExecutionContext.GitOperation)
                ?.gitRequest?.repository
                ?: repositoryHint

            val newGitRequest = GitOperationRequest(
                repository = priorRepo,
                commit = CommitRequest(
                    message = message,
                    files = files,
                    issueNumber = issueNumber,
                ),
            )

            return ExecutionRequest(
                context = ExecutionContext.GitOperation(
                    executorId = originalContext.executorId,
                    ticket = originalContext.ticket,
                    task = originalContext.task,
                    instructions = originalContext.instructions,
                    gitRequest = newGitRequest,
                    knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
                ),
                constraints = originalRequest.constraints,
            )
        }

        private companion object {
            private val json = Json { ignoreUnknownKeys = true }
        }
    }
}
