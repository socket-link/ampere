package link.socket.ampere.agents.tools

import co.touchlab.kermit.Logger
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.tools.registry.ToolRegistry

/**
 * ToolInitializer - Discovers and registers local FunctionTools at application startup.
 *
 * This is the "nervous system wiring" phase where we establish the baseline set of
 * tools available to agents before MCP servers are even contacted.
 *
 * Key responsibilities:
 * - Define all local function tools in one place for visibility
 * - Register them with the ToolRegistry
 * - Handle registration failures gracefully
 * - Provide observability through logging
 */

/**
 * Creates the complete set of local function tools available to agents.
 *
 * This factory function explicitly instantiates each local tool with its metadata
 * and execution function. By keeping all tools in one function, it's easy to:
 * - See the full capabilities at a glance
 * - Add new tools
 * - Modify existing ones
 * - Understand what agents can do locally
 *
 * Each tool wraps an execution function that performs the actual work. The functions
 * themselves delegate to service classes for the real implementation, keeping this
 * factory clean and focused on tool definitions.
 *
 * @return List of all local FunctionTools
 */
fun createLocalToolSet(): List<FunctionTool<*>> {
    return listOf(
        // WriteCode tool - Allows agents to write or modify source code files
        FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write_code",
            name = "Write Code",
            description = "Writes or modifies source code files in the workspace. " +
                "Requires specific file paths and code content. " +
                "Use this to implement features, fix bugs, or refactor code.",
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            executionFunction = { request ->
                executeWriteCode(request)
            }
        ),

        // ReadCode tool - Allows agents to read source code files
        FunctionTool<ExecutionContext.Code.ReadCode>(
            id = "read_code",
            name = "Read Code",
            description = "Reads source code files from the workspace. " +
                "Requires specific file paths to read. " +
                "Use this to understand existing code before making changes.",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                executeReadCode(request)
            }
        ),

        // AskHuman tool - Escalates decisions to human operators
        FunctionTool<ExecutionContext>(
            id = "ask_human",
            name = "Ask Human",
            description = "Escalates a decision or question to a human operator for approval or guidance. " +
                "Use this when facing ambiguous requirements, risky operations, " +
                "or when explicit human judgment is needed.",
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            executionFunction = { request ->
                executeAskHuman(request)
            }
        ),

        // CreateTicket tool - Creates tasks in the issue tracking system
        FunctionTool<ExecutionContext>(
            id = "create_ticket",
            name = "Create Ticket",
            description = "Creates a new ticket in the issue tracking system. " +
                "Use this to delegate work, track bugs, or create follow-up tasks.",
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            executionFunction = { request ->
                executeCreateTicket(request)
            }
        ),

        // RunTests tool - Executes test suites
        FunctionTool<ExecutionContext>(
            id = "run_tests",
            name = "Run Tests",
            description = "Executes the test suite to validate code changes. " +
                "Use this to ensure code quality and prevent regressions.",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                executeRunTests(request)
            }
        ),
    )
}

/**
 * Initializes local function tools by registering them with the ToolRegistry.
 *
 * This function should be called early in application startup, before agents
 * start running. It:
 * 1. Gets all local tools from createLocalToolSet()
 * 2. Attempts to register each tool with the registry
 * 3. Logs successful registrations
 * 4. Handles failures gracefully (logs warning but continues)
 * 5. Returns a Result indicating overall success/failure
 *
 * Individual tool registration failures don't crash the application - they're
 * logged as warnings and the system continues with the remaining tools. This
 * ensures maximum availability even if some tools are broken.
 *
 * @param registry The ToolRegistry to register tools with
 * @param logger Optional logger for observability (defaults to Logger with tag "ToolInitializer")
 * @return Result indicating success or failure, with count of successfully registered tools
 */
suspend fun initializeLocalTools(
    registry: ToolRegistry,
    logger: Logger = Logger.withTag("ToolInitializer")
): Result<ToolInitializationResult> {
    logger.i { "Starting local tool initialization..." }

    val tools = try {
        createLocalToolSet()
    } catch (e: Exception) {
        logger.e(e) { "Failed to create local tool set" }
        return Result.failure(e)
    }

    logger.i { "Discovered ${tools.size} local tools to register" }

    var successCount = 0
    var failureCount = 0
    val failures = mutableListOf<ToolRegistrationFailure>()

    tools.forEach { tool ->
        try {
            registry.registerTool(tool).fold(
                onSuccess = {
                    logger.i { "✓ Registered tool: ${tool.name} (${tool.id}) [autonomy: ${tool.requiredAgentAutonomy}]" }
                    successCount++
                },
                onFailure = { error ->
                    logger.w(error) { "✗ Failed to register tool: ${tool.name} (${tool.id})" }
                    failureCount++
                    failures.add(
                        ToolRegistrationFailure(
                            toolId = tool.id,
                            toolName = tool.name,
                            error = error.message ?: "Unknown error"
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.w(e) { "✗ Exception while registering tool: ${tool.name} (${tool.id})" }
            failureCount++
            failures.add(
                ToolRegistrationFailure(
                    toolId = tool.id,
                    toolName = tool.name,
                    error = e.message ?: "Unknown exception"
                )
            )
        }
    }

    val result = ToolInitializationResult(
        totalTools = tools.size,
        successfulRegistrations = successCount,
        failedRegistrations = failureCount,
        failures = failures
    )

    if (failureCount > 0) {
        logger.w {
            "Local tool initialization completed with errors: " +
                "$successCount succeeded, $failureCount failed"
        }
    } else {
        logger.i {
            "Local tool initialization completed successfully: " +
                "all $successCount tools registered"
        }
    }

    return Result.success(result)
}

/**
 * Result of tool initialization containing statistics and failure details.
 */
data class ToolInitializationResult(
    val totalTools: Int,
    val successfulRegistrations: Int,
    val failedRegistrations: Int,
    val failures: List<ToolRegistrationFailure>
) {
    /** Whether all tools were registered successfully */
    val isFullSuccess: Boolean
        get() = failedRegistrations == 0

    /** Whether at least some tools were registered */
    val isPartialSuccess: Boolean
        get() = successfulRegistrations > 0
}

/**
 * Details about a failed tool registration.
 */
data class ToolRegistrationFailure(
    val toolId: String,
    val toolName: String,
    val error: String
)

// ==================== TOOL EXECUTION FUNCTIONS ====================
// These are the actual implementations that tools delegate to.
// In a production system, these would delegate to service classes.

/**
 * Executes the WriteCode tool.
 * This is a placeholder implementation that will be replaced with actual code writing logic.
 */
private suspend fun executeWriteCode(
    request: ExecutionRequest<ExecutionContext.Code.WriteCode>
): Outcome {
    // TODO: Implement actual code writing logic
    // This should integrate with the workspace and file system
    val now = Clock.System.now()
    return ExecutionOutcome.CodeChanged.Success(
        executorId = request.context.executorId,
        ticketId = request.context.ticket.id,
        taskId = request.context.task.id,
        executionStartTimestamp = now,
        executionEndTimestamp = now,
        changedFiles = request.context.instructionsPerFilePath.map { it.first },
        validation = link.socket.ampere.agents.execution.results.ExecutionResult(
            codeChanges = null,
            compilation = null,
            linting = null,
            tests = null
        )
    )
}

/**
 * Executes the ReadCode tool.
 * This is a placeholder implementation that will be replaced with actual code reading logic.
 */
private suspend fun executeReadCode(
    request: ExecutionRequest<ExecutionContext.Code.ReadCode>
): Outcome {
    // TODO: Implement actual code reading logic
    // This should integrate with the workspace and file system
    val now = Clock.System.now()
    return ExecutionOutcome.CodeReading.Success(
        executorId = request.context.executorId,
        ticketId = request.context.ticket.id,
        taskId = request.context.task.id,
        executionStartTimestamp = now,
        executionEndTimestamp = now,
        readFiles = request.context.filePathsToRead.map { it to "// File content placeholder" }
    )
}

/**
 * Executes the AskHuman tool.
 * This is a placeholder implementation that will be replaced with actual human escalation logic.
 */
private suspend fun executeAskHuman(
    request: ExecutionRequest<ExecutionContext>
): Outcome {
    // TODO: Implement actual human escalation logic
    // This should integrate with the escalation system
    val now = Clock.System.now()
    return ExecutionOutcome.NoChanges.Success(
        executorId = request.context.executorId,
        ticketId = request.context.ticket.id,
        taskId = request.context.task.id,
        executionStartTimestamp = now,
        executionEndTimestamp = now,
        message = "AskHuman tool executed (placeholder): ${request.context.instructions}"
    )
}

/**
 * Executes the CreateTicket tool.
 * This is a placeholder implementation that will be replaced with actual ticket creation logic.
 */
private suspend fun executeCreateTicket(
    request: ExecutionRequest<ExecutionContext>
): Outcome {
    // TODO: Implement actual ticket creation logic
    // This should integrate with the ticket/event system
    val now = Clock.System.now()
    return ExecutionOutcome.NoChanges.Success(
        executorId = request.context.executorId,
        ticketId = request.context.ticket.id,
        taskId = request.context.task.id,
        executionStartTimestamp = now,
        executionEndTimestamp = now,
        message = "CreateTicket tool executed (placeholder) for task: ${request.context.task.id}"
    )
}

/**
 * Executes the RunTests tool.
 * This is a placeholder implementation that will be replaced with actual test execution logic.
 */
private suspend fun executeRunTests(
    request: ExecutionRequest<ExecutionContext>
): Outcome {
    // TODO: Implement actual test execution logic
    // This should integrate with the test runner
    val now = Clock.System.now()
    return ExecutionOutcome.NoChanges.Success(
        executorId = request.context.executorId,
        ticketId = request.context.ticket.id,
        taskId = request.context.task.id,
        executionStartTimestamp = now,
        executionEndTimestamp = now,
        message = "RunTests tool executed (placeholder)"
    )
}
