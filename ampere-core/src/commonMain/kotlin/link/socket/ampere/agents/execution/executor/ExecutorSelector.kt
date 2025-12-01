package link.socket.ampere.agents.execution.executor

import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * ExecutorSelector - Utility for selecting the appropriate executor for a tool.
 *
 * Different tool types require different executors:
 * - FunctionTool: Executed by a local function executor (or directly invoked)
 * - McpTool: Executed by McpExecutor
 *
 * This selector encapsulates the logic for matching tools to executors,
 * making it easier to add new tool types in the future.
 *
 * Usage:
 * ```
 * val executors = mapOf(
 *     "function" to functionExecutor,
 *     "mcp" to mcpExecutor,
 * )
 *
 * val executor = selectExecutorForTool(tool, executors)
 * val result = executor.execute(request, tool)
 * ```
 */
object ExecutorSelector {

    /**
     * Selects the appropriate executor for a given tool.
     *
     * This function uses pattern matching on the sealed Tool interface
     * to determine which executor can handle the tool.
     *
     * @param tool The tool to execute
     * @param availableExecutors Map of executor types to executor instances
     * @return The executor that can handle this tool, or null if none found
     */
    fun selectExecutorForTool(
        tool: Tool<*>,
        availableExecutors: Map<String, Executor>,
    ): Executor? {
        return when (tool) {
            is FunctionTool<*> -> {
                // FunctionTools can be executed by a function executor
                // or by calling tool.execute() directly
                availableExecutors["function"]
            }

            is McpTool -> {
                // McpTools require the MCP executor
                availableExecutors["mcp"]
            }
        }
    }

    /**
     * Determines the executor type needed for a tool.
     *
     * Returns the executor type key (e.g., "function", "mcp") for the given tool.
     * This can be used to look up the appropriate executor from a registry.
     *
     * @param tool The tool to check
     * @return The executor type key, or null if unknown
     */
    fun getExecutorTypeForTool(tool: Tool<*>): String? {
        return when (tool) {
            is FunctionTool<*> -> "function"
            is McpTool -> "mcp"
        }
    }

    /**
     * Checks if an executor can handle a given tool.
     *
     * This validates that the executor's type matches the tool's requirements.
     *
     * @param executor The executor to check
     * @param tool The tool to execute
     * @return True if the executor can handle the tool, false otherwise
     */
    fun canExecutorHandleTool(
        executor: Executor,
        tool: Tool<*>,
    ): Boolean {
        return when {
            tool is McpTool && executor is McpExecutor -> true
            tool is FunctionTool<*> -> {
                // FunctionTools can be handled by any executor that supports functions
                // For now, we accept any non-MCP executor
                executor !is McpExecutor
            }
            else -> false
        }
    }
}

/**
 * Extension function to select an executor for this tool.
 *
 * Convenience method that can be called on Tool instances.
 *
 * @param availableExecutors Map of executor types to executor instances
 * @return The executor that can handle this tool, or null if none found
 */
fun Tool<*>.selectExecutor(
    availableExecutors: Map<String, Executor>,
): Executor? {
    return ExecutorSelector.selectExecutorForTool(this, availableExecutors)
}

/**
 * Extension function to get the executor type for this tool.
 *
 * @return The executor type key, or null if unknown
 */
fun Tool<*>.getExecutorType(): String? {
    return ExecutorSelector.getExecutorTypeForTool(this)
}
