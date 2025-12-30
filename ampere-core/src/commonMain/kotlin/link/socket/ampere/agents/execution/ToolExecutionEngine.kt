package link.socket.ampere.agents.execution

import kotlinx.coroutines.flow.last
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Engine for executing tools with LLM-generated parameters.
 *
 * This component bridges the gap between high-level intent and concrete tool
 * execution. It uses the LLM to generate the specific parameters each tool needs,
 * then executes the tool through the executor framework.
 *
 * The execution flow:
 * 1. Extract intent from the execution request
 * 2. Use a ParameterStrategy to build tool-specific prompts
 * 3. Call LLM to generate parameters
 * 4. Enrich the execution request with generated parameters
 * 5. Execute via the executor
 * 6. Return the execution outcome
 *
 * Usage:
 * ```kotlin
 * val engine = ToolExecutionEngine(llmService, executor, executorId)
 *
 * // Register parameter strategies for different tools
 * engine.registerStrategy("create_issues", ProjectParams.IssueCreation(...))
 * engine.registerStrategy("ask_human", ProjectParams.HumanEscalation(...))
 *
 * // Execute a tool
 * val outcome = engine.execute(tool, request)
 * ```
 *
 * @property llmService The LLM service for generating parameters
 * @property executor The executor for running tools
 * @property executorId ID of the executor/agent running tools
 */
class ToolExecutionEngine(
    private val llmService: AgentLLMService,
    private val executor: Executor,
    private val executorId: ExecutorId,
) {

    private val strategies = mutableMapOf<String, ParameterStrategy>()

    /**
     * Registers a parameter generation strategy for a tool.
     *
     * @param toolId The tool ID this strategy handles
     * @param strategy The strategy for generating parameters
     */
    fun registerStrategy(toolId: String, strategy: ParameterStrategy) {
        strategies[toolId] = strategy
    }

    /**
     * Executes a tool with LLM-generated parameters.
     *
     * @param tool The tool to execute
     * @param request The execution request containing context and intent
     * @return ExecutionOutcome indicating success or failure
     */
    suspend fun execute(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        val startTime = Clock.System.now()

        // Extract intent from request
        val intent = request.context.instructions
        if (intent.isBlank()) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "Cannot execute tool: no intent found in request",
            )
        }

        // Check for MCP tools (not yet supported)
        if (tool is McpTool) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "MCP tool execution not yet supported",
            )
        }

        // Get strategy for this tool
        val strategy = strategies[tool.id]
        if (strategy == null) {
            // No strategy registered - try generic execution
            return executeGenericTool(tool, request, startTime)
        }

        // Generate parameters using strategy
        val prompt = strategy.buildPrompt(tool, request, intent)
        val enrichedRequest = try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = strategy.systemMessage,
                maxTokens = strategy.maxTokens,
            )
            strategy.parseAndEnrichRequest(jsonResponse.rawJson, request)
        } catch (e: Exception) {
            return createFailure(
                request = request,
                startTime = startTime,
                message = "Failed to generate parameters: ${e.message}",
            )
        }

        // Execute via executor
        return executeViaExecutor(tool, enrichedRequest, startTime, request)
    }

    /**
     * Executes a tool without a registered strategy (generic execution).
     */
    private suspend fun executeGenericTool(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        startTime: Instant,
    ): ExecutionOutcome {
        // For tools without strategies, try direct execution
        return try {
            executeViaExecutor(tool, request, startTime, request)
        } catch (e: Exception) {
            createFailure(
                request = request,
                startTime = startTime,
                message = "Generic tool execution failed: ${e.message}",
            )
        }
    }

    /**
     * Executes the tool through the executor framework.
     */
    private suspend fun executeViaExecutor(
        tool: Tool<*>,
        enrichedRequest: ExecutionRequest<*>,
        startTime: Instant,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionOutcome {
        return try {
            when (tool) {
                is FunctionTool<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val typedTool = tool as Tool<ExecutionContext>

                    @Suppress("UNCHECKED_CAST")
                    val typedRequest = enrichedRequest as ExecutionRequest<ExecutionContext>

                    val statusFlow = executor.execute(typedRequest, typedTool)
                    val finalStatus = statusFlow.last()

                    when (finalStatus) {
                        is ExecutionStatus.Completed -> finalStatus.result
                        is ExecutionStatus.Failed -> finalStatus.result
                        else -> createFailure(
                            request = originalRequest,
                            startTime = startTime,
                            message = "Unexpected execution status: ${finalStatus::class.simpleName}",
                        )
                    }
                }
                is McpTool -> {
                    createFailure(
                        request = originalRequest,
                        startTime = startTime,
                        message = "MCP tool execution not yet supported",
                    )
                }
            }
        } catch (e: Exception) {
            createFailure(
                request = originalRequest,
                startTime = startTime,
                message = "Tool execution failed: ${e.message}",
            )
        }
    }

    /**
     * Creates a failure outcome.
     */
    private fun createFailure(
        request: ExecutionRequest<*>,
        startTime: Instant,
        message: String,
    ): ExecutionOutcome {
        return ExecutionOutcome.NoChanges.Failure(
            executorId = executorId,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            message = message,
        )
    }
}

/**
 * Strategy for generating tool-specific parameters.
 *
 * Each tool type can have its own strategy that knows how to:
 * - Build the appropriate LLM prompt
 * - Parse the LLM response
 * - Create the enriched ExecutionRequest with generated parameters
 *
 * Implementations should be tool-specific, e.g.:
 * - ProjectParams.IssueCreation for ToolCreateIssues
 * - CodeParams.CodeWriting for ToolWriteCodeFile
 * - ProjectParams.HumanEscalation for ToolAskHuman
 */
interface ParameterStrategy {

    /** System message for the LLM call */
    val systemMessage: String
        get() = "You are a parameter generation system. Generate parameters as valid JSON."

    /** Maximum tokens for the LLM response */
    val maxTokens: Int
        get() = 2000

    /**
     * Builds the LLM prompt for generating parameters.
     *
     * @param tool The tool being executed
     * @param request The original execution request
     * @param intent The high-level intent to accomplish
     * @return The prompt string for the LLM
     */
    fun buildPrompt(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
    ): String

    /**
     * Parses the LLM response and creates an enriched execution request.
     *
     * @param jsonResponse The raw JSON response from the LLM
     * @param originalRequest The original execution request
     * @return An enriched execution request with generated parameters
     */
    fun parseAndEnrichRequest(
        jsonResponse: String,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionRequest<*>
}
