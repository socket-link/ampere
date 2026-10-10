package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeParams
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

const val READ_CODE_FILE_TOOL_ID: String = "read_code_file"

/**
 * Creates a FunctionTool that reads code files from the workspace.
 *
 * Wraps the same platform-side `executeReadCodebase` implementation as
 * [ToolReadCodebase] but exposes the tool under the canonical
 * `read_code_file` id that the declarative role-code spark references, and
 * ships with the [CodeParams.CodeReading]
 * parameter strategy attached so an agent that wants the tool does not
 * need to register a strategy separately.
 *
 * Every requested path is checked against the dispatching agent's
 * [FileAccessScope] before the platform read runs (AMPR-414), and the whole
 * call is refused if any path is out of scope — a partial read would hand the
 * model a file list it cannot tell from a complete one. The refusal is an
 * [ExecutionOutcome.CodeReading.Failure] rather than a thrown exception.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to
 *   use this tool.
 * @param parameterStrategy Override the default code-reading strategy
 *   (e.g. for testing). Pass `null` to disable param generation.
 */
fun ToolReadCodeFile(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    parameterStrategy: ParameterStrategy? = CodeParams.CodeReading(),
): FunctionTool<ExecutionContext.Code.ReadCode> {
    return FunctionTool(
        id = READ_CODE_FILE_TOOL_ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            val context = executionRequest.context
            val scope = executionRequest.fileAccessScope
            val refusals = scope?.refusedReads(context.filePathsToRead).orEmpty()

            if (scope == null || refusals.isEmpty()) {
                executeReadCodebase(context)
            } else {
                outOfScopeFailure(context, refusals, scope)
            }
        },
        parameterStrategy = parameterStrategy,
    )
}

/**
 * The paths in [paths] this scope will not permit a read of, each paired with
 * the reason — the forbidden pattern that blocked it, or the absence of a
 * matching read pattern.
 */
internal fun FileAccessScope.refusedReads(paths: List<String>): List<Pair<String, String>> =
    paths.mapNotNull { path ->
        when {
            allowsRead(path) -> null
            else -> path to (
                forbiddingPattern(path)
                    ?.let { "blocked by forbidden pattern \"$it\"" }
                    ?: "no read pattern permits it"
                )
        }
    }

/**
 * Refusal outcome for a read the spark stack does not permit.
 *
 * Reported before the platform read runs, so `partiallyReadFiles` is empty by
 * construction: nothing was opened.
 */
private fun outOfScopeFailure(
    context: ExecutionContext.Code.ReadCode,
    refusals: List<Pair<String, String>>,
    scope: FileAccessScope,
): ExecutionOutcome.CodeReading.Failure {
    val timestamp = Clock.System.now()
    return ExecutionOutcome.CodeReading.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = timestamp,
        executionEndTimestamp = timestamp,
        partiallyReadFiles = emptyList(),
        error = ExecutionError(
            type = ExecutionError.Type.WORKSPACE_ERROR,
            message = "Refused $READ_CODE_FILE_TOOL_ID: " +
                refusals.joinToString { (path, reason) -> "\"$path\" ($reason)" } +
                " outside the file access scope the agent's spark stack permits.",
            details = "read patterns: ${scope.readPatterns.sorted()}; " +
                "forbidden patterns: ${scope.forbiddenPatterns.sorted()}",
            isRetryable = false,
        ),
    )
}

private const val NAME = "Read Code File"
private const val DESCRIPTION = "Reads one or more code files from the current workspace."
