package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeParams
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

expect suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged

const val WRITE_CODE_FILE_TOOL_ID: String = "write_code_file"

/**
 * Creates a FunctionTool that writes code files to the workspace.
 *
 * The tool ships with [CodeParams.CodeWriting] as its
 * [ParameterStrategy] by default so the sub-prompt that converts a
 * high-level intent into "files to write" lives with the tool rather
 * than being externally registered by every agent that wants the tool.
 *
 * Every path the strategy produced is checked against the dispatching
 * agent's [FileAccessScope] before the platform write runs (AMPR-414): the
 * whole call is refused if *any* path is out of scope, so a batch cannot
 * half-apply, and the refusal is an [ExecutionOutcome.CodeChanged.Failure]
 * rather than a thrown exception.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to
 *   use this tool.
 * @param parameterStrategy Override the default code-writing strategy
 *   (e.g. for testing). Pass `null` to disable param generation.
 */
fun ToolWriteCodeFile(
    requiredAgentAutonomy: AgentActionAutonomy,
    parameterStrategy: ParameterStrategy? = CodeParams.CodeWriting(),
): FunctionTool<ExecutionContext.Code.WriteCode> {
    return FunctionTool(
        id = WRITE_CODE_FILE_TOOL_ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            // TODO: Handle execution request constraints
            val context = executionRequest.context
            val scope = executionRequest.fileAccessScope
            val refusals = scope
                ?.refusedWrites(context.instructionsPerFilePath.map { it.first })
                .orEmpty()

            if (scope == null || refusals.isEmpty()) {
                executeWriteCodeFile(context)
            } else {
                outOfScopeFailure(context, refusals, scope)
            }
        },
        parameterStrategy = parameterStrategy,
    )
}

/**
 * The paths in [paths] this scope will not permit a write to, each paired
 * with the reason — the forbidden pattern that blocked it, or the absence of
 * a matching write pattern.
 */
internal fun FileAccessScope.refusedWrites(paths: List<String>): List<Pair<String, String>> =
    paths.mapNotNull { path ->
        when {
            allowsWrite(path) -> null
            else -> path to (
                forbiddingPattern(path)
                    ?.let { "blocked by forbidden pattern \"$it\"" }
                    ?: "no write pattern permits it"
                )
        }
    }

/**
 * Refusal outcome for a write the spark stack does not permit.
 *
 * Reported before the platform write runs, so `partiallyChangedFiles` is
 * empty by construction: nothing was touched.
 */
private fun outOfScopeFailure(
    context: ExecutionContext.Code.WriteCode,
    refusals: List<Pair<String, String>>,
    scope: FileAccessScope,
): ExecutionOutcome.CodeChanged.Failure {
    val timestamp = Clock.System.now()
    return ExecutionOutcome.CodeChanged.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = timestamp,
        executionEndTimestamp = timestamp,
        partiallyChangedFiles = emptyList(),
        error = ExecutionError(
            type = ExecutionError.Type.WORKSPACE_ERROR,
            message = "Refused $WRITE_CODE_FILE_TOOL_ID: " +
                refusals.joinToString { (path, reason) -> "\"$path\" ($reason)" } +
                " outside the file access scope the agent's spark stack permits.",
            details = "write patterns: ${scope.writePatterns.sorted()}; " +
                "forbidden patterns: ${scope.forbiddenPatterns.sorted()}",
            isRetryable = false,
        ),
    )
}

private const val NAME = "Write Code File"
private const val DESCRIPTION = "Writes a code file in the current workspace."
