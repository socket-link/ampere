package link.socket.ampere.agents.tools.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * The single request -> `tools/call` arguments translation for MCP dispatch.
 *
 * Both MCP dispatch paths —
 * [McpToolExecutor] (reached via `McpTool.execute()`) and
 * [McpExecutor][link.socket.ampere.agents.execution.executor.McpExecutor] (reached via the
 * [Executor][link.socket.ampere.agents.execution.executor.Executor] framework) — build their
 * arguments here so the two cannot drift. They did drift: `McpToolExecutor` sent
 * `tool.inputSchema`, the tool's own JSON *schema*, where its arguments belong (AMPR-341).
 *
 * The arguments are derived from the request's context, which is all a dispatch-time caller
 * has: an [ExecutionRequest] carries no per-call MCP argument object. That makes this an
 * envelope describing the work, not a payload shaped to any particular tool's `inputSchema`
 * — filling a specific tool's parameters needs a
 * [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy] and a context
 * that can carry what it generates, neither of which exists yet. Callers that do know the
 * real arguments (today [ExecuteStep][link.socket.ampere.propel.ExecuteStep]) pass them
 * straight to the connection and never come through here.
 */
internal object McpCallArguments {

    /**
     * Builds the `tools/call` arguments for [request].
     *
     * @param request the execution request being dispatched
     * @return a JSON object describing the requested work
     */
    fun forRequest(request: ExecutionRequest<*>): JsonElement = buildJsonObject {
        val context = request.context

        put("instructions", context.instructions)

        val task = context.task
        put("taskId", task.id)
        // Task is a sealed interface - only some subtypes carry a description.
        if (task is Task.CodeChange) {
            put("taskDescription", task.description)
        }

        put("ticketId", context.ticket.id)
        put("ticketDescription", context.ticket.description)

        put("executorId", context.executorId)

        if (context.knowledgeFromPastMemory.isNotEmpty()) {
            put("pastAttempts", context.knowledgeFromPastMemory.size.toString())
        }
    }
}
