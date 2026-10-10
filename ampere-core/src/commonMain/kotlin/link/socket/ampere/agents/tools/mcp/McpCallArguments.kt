package link.socket.ampere.agents.tools.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * The single request -> `tools/call` arguments translation for MCP dispatch.
 *
 * Every MCP dispatch builds its arguments here so no two can drift. They did drift:
 * `McpToolExecutor` sent `tool.inputSchema`, the tool's own JSON *schema*, where its
 * arguments belong (AMPR-341). The dispatches are
 * [McpToolExecutor], reached both via `McpTool.execute()` and from
 * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]'s MCP
 * branch (AMPR-401), and
 * [McpExecutor][link.socket.ampere.agents.execution.executor.McpExecutor] in the
 * [Executor][link.socket.ampere.agents.execution.executor.Executor] framework, which
 * nothing constructs.
 *
 * The arguments are derived from the request's context, which is all a dispatch-time caller
 * has. That makes this an envelope describing the work, not a payload shaped to any
 * particular tool's `inputSchema`. Filling a specific tool's parameters needs a
 * [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy], and
 * [ExecutionRequest.arguments] is now the generic carrier for what one produces (AMPR-411).
 * This still does not read it, because nothing on the MCP path fills it: the schema-derived
 * strategy is keyed on
 * [FunctionTool.argumentSchema][link.socket.ampere.agents.execution.tools.FunctionTool.argumentSchema],
 * so an [McpTool][link.socket.ampere.agents.execution.tools.McpTool] never reaches it, and
 * `inputSchema` — the schema an MCP tool does declare — is not wired to that path. Reading
 * the field here would read one nothing sets. Giving an MCP tool the same treatment is the
 * follow-up that would make it worth reading.
 *
 * No caller passes real arguments straight to a connection any more: the one that did,
 * `propel/ExecuteStep`, was the dead half of a two-path plug dispatch and is gone.
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
