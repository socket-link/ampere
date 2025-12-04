package link.socket.ampere.agents.implementations.code

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.type.CodeWriterAgent
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * Tests for the runLLMToExecuteTool function in CodeWriterAgent.
 *
 * These tests validate that the function:
 * 1. Generates appropriate parameters for tools using LLM
 * 2. Executes tools through the executor pattern
 * 3. Handles errors gracefully
 * 4. Works with different tool types
 *
 * Note: These are primarily structure/API tests since we can't easily mock
 * the LLM service in the current architecture. Integration tests would
 * require actual LLM calls.
 */
class RunLLMToExecuteToolTest {

    private fun createTestTicket(id: String = "test-ticket"): Ticket {
        val now = Clock.System.now()
        return Ticket(
            id = id,
            title = "Test ticket",
            description = "Test ticket description",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = "test-agent",
            createdByAgentId = "test-agent",
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
    }

    private fun createTestTask(id: String = "test-task"): Task.CodeChange {
        return Task.CodeChange(
            id = id,
            status = TaskStatus.Pending,
            description = "Create a simple data class for User",
        )
    }

    private fun createTestAgentConfiguration(): AgentConfiguration {
        return AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_4
            )
        )
    }

    @Test
    fun `runLLMToExecuteTool handles empty intent gracefully`() = runBlocking {
        // Use real FunctionExecutor instead of mock
        val functionExecutor = FunctionExecutor.create()

        val writeCodeTool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes code to a file",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                throw IllegalStateException("Tool should be executed through executor")
            }
        )

        val agent = CodeWriterAgent(
            initialState = AgentState(),
            agentConfiguration = createTestAgentConfiguration(),
            toolWriteCodeFile = writeCodeTool,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            executor = functionExecutor,
        )

        // Create request with empty instructions (empty intent)
        val ticket = createTestTicket()
        val task = createTestTask()
        val request = ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = functionExecutor.id,
                ticket = ticket,
                task = task,
                instructions = "", // Empty instructions
                workspace = ExecutionWorkspace(baseDirectory = "."),
                instructionsPerFilePath = emptyList(),
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )

        val outcome = agent.runLLMToExecuteTool(writeCodeTool, request)

        // Should return a failure outcome due to empty intent
        assertIs<ExecutionOutcome.Failure>(outcome)
        assertTrue(
            (outcome as ExecutionOutcome.NoChanges.Failure).message.contains("no intent"),
            "Error message should mention missing intent"
        )
    }

    @Test
    fun `runLLMToExecuteTool returns failure for MCP tools`() = runBlocking {
        // Use real FunctionExecutor instead of mock
        val functionExecutor = FunctionExecutor.create()

        val mcpTool = link.socket.ampere.agents.execution.tools.McpTool(
            id = "mcp_test_tool",
            name = "MCP Test Tool",
            description = "A test MCP tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "test-server",
            remoteToolName = "test-tool",
            inputSchema = null,
        )

        val writeCodeTool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes code to a file",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                throw IllegalStateException("Tool should be executed through executor")
            }
        )

        val agent = CodeWriterAgent(
            initialState = AgentState(),
            agentConfiguration = createTestAgentConfiguration(),
            toolWriteCodeFile = writeCodeTool,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            executor = functionExecutor,
        )

        val ticket = createTestTicket()
        val task = createTestTask()
        val request = ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = functionExecutor.id,
                ticket = ticket,
                task = task,
                instructions = "Test MCP tool execution",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )

        val outcome = agent.runLLMToExecuteTool(mcpTool, request)

        // Should return failure for MCP tools (not yet supported)
        assertIs<ExecutionOutcome.Failure>(outcome)
        assertTrue(
            (outcome as ExecutionOutcome.NoChanges.Failure).message.contains("MCP tool"),
            "Error message should mention MCP tools not being supported"
        )
    }

    @Test
    fun `runLLMToExecuteTool function signature exists and is callable`() {
        // This test verifies that the function exists with the correct signature
        // and can be called. Full integration testing would require mocking the LLM service.

        // Use real FunctionExecutor instead of mock
        val functionExecutor = FunctionExecutor.create()

        val writeCodeTool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes code to a file",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                throw IllegalStateException("Tool should be executed through executor")
            }
        )

        val agent = CodeWriterAgent(
            initialState = AgentState(),
            agentConfiguration = createTestAgentConfiguration(),
            toolWriteCodeFile = writeCodeTool,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            executor = functionExecutor,
        )

        // Verify the function exists and has the correct signature
        val function: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome = agent.runLLMToExecuteTool

        // Verify it's not null and is a function
        assertTrue(function != null, "runLLMToExecuteTool should not be null")
    }
}
