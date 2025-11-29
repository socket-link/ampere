package link.socket.ampere.agents.execution.executor

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.errors.ExecutionError
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.status.ExecutionStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.EventId
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.McpServerManager
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for McpExecutor - MCP tool execution layer.
 *
 * These tests validate:
 * 1. End-to-end execution flow with successful results
 * 2. Error handling when MCP server is unavailable
 * 3. Type safety (rejecting non-MCP tools)
 * 4. Request translation (ExecutionRequest -> MCP arguments)
 * 5. Result translation (ToolCallResult -> ExecutionOutcome)
 * 6. Progress event emission throughout execution
 * 7. Edge case handling (null results, exceptions, timeouts)
 * 8. Multiple tool executions in sequence
 */
class McpExecutorTest {

    /**
     * Test 1: Execute an MCP tool successfully end-to-end.
     *
     * Validates the complete flow from request to outcome.
     */
    @Test
    fun `test successful MCP tool execution end-to-end`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        // Configure connection to return successful result
        mockConnection.toolCallResult = Result.success(
            ToolCallResult(
                content = listOf(
                    ContentItem(
                        type = "text",
                        text = "Tool executed successfully: concatenated 'hello' and 'world'",
                    ),
                ),
                isError = false,
            ),
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null, // Not needed for this test
        )

        val tool = McpTool(
            id = "test-server:concat",
            name = "concat",
            description = "Concatenates two strings",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "concat",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert
        assertTrue(statuses.isNotEmpty(), "Should emit at least one status")

        // Should emit Started status
        val startedStatus = statuses.filterIsInstance<ExecutionStatus.Started>()
        assertTrue(startedStatus.isNotEmpty(), "Should emit Started status")

        // Should emit Planning status
        val planningStatus = statuses.filterIsInstance<ExecutionStatus.Planning>()
        assertTrue(planningStatus.isNotEmpty(), "Should emit Planning status")

        // Final status should be Completed
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Completed>(finalStatus, "Final status should be Completed")

        // Verify outcome is success
        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.Success>(outcome, "Outcome should be Success")
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome, "Outcome should be NoChanges.Success")

        // Verify result message contains expected text
        assertTrue(
            outcome.message.contains("concatenated"),
            "Result message should contain tool output"
        )
    }

    /**
     * Test 2: Execution fails when MCP server is unavailable.
     *
     * Validates graceful degradation when server connection doesn't exist.
     */
    @Test
    fun `test execution fails when MCP server unavailable`() = runTest {
        // Arrange
        val mockManager = MockMcpServerManager()
        // Don't add any connections - server is unavailable

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "unavailable-server:tool",
            name = "tool",
            description = "Some tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "unavailable-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert
        assertTrue(statuses.isNotEmpty(), "Should emit at least one status")

        // Final status should be Failed
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Failed>(finalStatus, "Final status should be Failed")

        // Verify outcome is failure
        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.Failure>(outcome, "Outcome should be Failure")

        // Verify error message mentions server unavailability
        val message = (outcome as ExecutionOutcome.NoChanges.Failure).message
        assertTrue(
            message.contains("not connected"),
            "Error message should mention server not connected"
        )
    }

    /**
     * Test 3: Reject non-MCP tools with clear error.
     *
     * Validates type safety - McpExecutor should only accept McpTool instances.
     */
    @Test
    fun `test execution rejects non-MCP tools`() = runTest {
        // Arrange
        val mockManager = MockMcpServerManager()

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        // Create a FunctionTool (not an McpTool)
        val functionTool = FunctionTool<ExecutionContext>(
            id = "function-tool",
            name = "some-function",
            description = "A function tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            executionFunction = { ExecutionOutcome.blank },
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, functionTool)
        val statuses = statusFlow.toList()

        // Assert
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Failed>(finalStatus, "Should reject non-MCP tool")

        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.Failure>(outcome)

        // Verify error message is clear
        val message = (outcome as ExecutionOutcome.NoChanges.Failure).message
        assertTrue(
            message.contains("not an MCP tool"),
            "Error message should explain type mismatch"
        )
    }

    /**
     * Test 4: Request translation extracts correct parameters.
     *
     * Validates the impedance matching from ExecutionRequest to MCP arguments.
     */
    @Test
    fun `test request translation to MCP arguments`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        var capturedArguments: JsonElement? = null

        // Capture arguments passed to invokeTool
        mockConnection.onInvokeTool = { _, arguments ->
            capturedArguments = arguments
            Result.success(
                ToolCallResult(
                    content = listOf(ContentItem(type = "text", text = "OK")),
                    isError = false,
                ),
            )
        }

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest(
            instructions = "Do something specific",
            taskDescription = "Task ABC",
        )

        // Act
        val statusFlow = executor.execute(request, tool)
        statusFlow.toList()

        // Assert
        assertNotNull(capturedArguments, "Should capture arguments")

        // Verify arguments contain expected fields
        val argsObj = capturedArguments.toString()
        assertTrue(argsObj.contains("instructions"), "Should include instructions")
        assertTrue(argsObj.contains("Do something specific"), "Should include actual instructions")
        assertTrue(argsObj.contains("taskDescription"), "Should include task description")
        assertTrue(argsObj.contains("Task ABC"), "Should include actual task description")
    }

    /**
     * Test 5: Result translation converts ToolCallResult to ExecutionOutcome.
     *
     * Validates the reverse impedance matching from MCP to agent abstractions.
     */
    @Test
    fun `test result translation from ToolCallResult to outcome`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        mockConnection.toolCallResult = Result.success(
            ToolCallResult(
                content = listOf(
                    ContentItem(type = "text", text = "First line"),
                    ContentItem(type = "text", text = "Second line"),
                ),
                isError = false,
            ),
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Completed>(finalStatus)

        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)

        // Verify both content items are included in message
        assertTrue(outcome.message.contains("First line"))
        assertTrue(outcome.message.contains("Second line"))
    }

    /**
     * Test 6: Progress events are emitted throughout execution.
     *
     * Validates observability - agents and dashboards can track execution progress.
     */
    @Test
    fun `test progress events emitted throughout execution`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        mockConnection.toolCallResult = Result.success(
            ToolCallResult(
                content = listOf(ContentItem(type = "text", text = "Done")),
                isError = false,
            ),
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert - verify sequence of status types
        assertTrue(statuses.size >= 3, "Should emit at least 3 statuses")

        // Check that each type is present
        assertTrue(
            statuses.any { it is ExecutionStatus.Started },
            "Should emit Started status"
        )
        assertTrue(
            statuses.any { it is ExecutionStatus.Planning },
            "Should emit Planning status"
        )
        assertTrue(
            statuses.any { it is ExecutionStatus.Completed },
            "Should emit Completed status"
        )

        // Verify only the final status has isClosed = true
        val closedStatuses = statuses.filter { it.isClosed }
        assertEquals(1, closedStatuses.size, "Only final status should be closed")
        assertEquals(statuses.last(), closedStatuses.first(), "Final status should be the closed one")
    }

    /**
     * Test 7: Handle edge case where MCP server returns null result.
     *
     * Validates defensive programming - null results don't crash the executor.
     */
    @Test
    fun `test handles null result from MCP server`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        // Return empty content
        mockConnection.toolCallResult = Result.success(
            ToolCallResult(
                content = emptyList(),
                isError = false,
            ),
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert - should handle gracefully, not crash
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Completed>(finalStatus, "Should complete successfully even with empty result")

        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)

        // Should provide default message for empty content
        assertTrue(
            outcome.message.contains("successfully") || outcome.message.contains("no output"),
            "Should have sensible default message"
        )
    }

    /**
     * Test 8: Exception handling during tool invocation.
     *
     * Validates resilience - exceptions are caught and converted to Failed status.
     */
    @Test
    fun `test exception handling during tool invocation`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        // Simulate an exception during invocation
        mockConnection.toolCallResult = Result.failure(
            Exception("Network timeout - connection lost"),
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Failed>(finalStatus, "Should emit Failed status on exception")

        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.Failure>(outcome)

        // Verify error details are included
        val message = (outcome as ExecutionOutcome.NoChanges.Failure).message
        assertTrue(
            message.contains("timeout") || message.contains("failed"),
            "Error message should include details: $message"
        )
    }

    /**
     * Test 9: Multiple tool executions in sequence.
     *
     * Validates that the executor is reusable across multiple invocations.
     */
    @Test
    fun `test multiple tool executions in sequence`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        var executionCount = 0

        mockConnection.onInvokeTool = { _, _ ->
            executionCount++
            Result.success(
                ToolCallResult(
                    content = listOf(ContentItem(type = "text", text = "Execution $executionCount")),
                    isError = false,
                ),
            )
        }

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        // Act - execute 3 times
        for (i in 1..3) {
            val request = createTestExecutionRequest()
            val statusFlow = executor.execute(request, tool)
            val statuses = statusFlow.toList()

            // Assert each execution completes successfully
            val finalStatus = statuses.last()
            assertIs<ExecutionStatus.Completed>(
                finalStatus,
                "Execution $i should complete successfully"
            )
        }

        // Verify all 3 executions happened
        assertEquals(3, executionCount, "Should execute 3 times")
    }

    /**
     * Test 10: MCP server connection is not active.
     *
     * Validates handling when connection exists but is not connected.
     */
    @Test
    fun `test handles inactive MCP server connection`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = false, // Connection exists but not active
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Failed>(finalStatus)

        val outcome = finalStatus.result
        val message = (outcome as ExecutionOutcome.NoChanges.Failure).message
        assertTrue(
            message.contains("not active"),
            "Error should mention connection not active"
        )
    }

    /**
     * Test 11: MCP tool returns error result.
     *
     * Validates handling when the tool executes but returns an error.
     */
    @Test
    fun `test handles MCP tool error result`() = runTest {
        // Arrange
        val mockConnection = MockMcpServerConnection(
            serverId = "test-server",
            isConnectedValue = true,
        )

        // Return error result from tool
        mockConnection.toolCallResult = Result.success(
            ToolCallResult(
                content = listOf(
                    ContentItem(type = "text", text = "Error: Invalid parameters provided"),
                ),
                isError = true,
            ),
        )

        val mockManager = MockMcpServerManager()
        mockManager.connections["test-server"] = mockConnection

        val executor = McpExecutor(
            id = "mcp-test",
            displayName = "Test MCP Executor",
            capabilities = ExecutorCapabilities(emptySet(), emptySet()),
            mcpServerManager = mockManager,
            eventBus = null,
        )

        val tool = McpTool(
            id = "test-server:tool",
            name = "tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULL,
            serverId = "test-server",
            remoteToolName = "tool",
        )

        val request = createTestExecutionRequest()

        // Act
        val statusFlow = executor.execute(request, tool)
        val statuses = statusFlow.toList()

        // Assert
        val finalStatus = statuses.last()
        assertIs<ExecutionStatus.Failed>(finalStatus, "Should be Failed when tool returns error")

        val outcome = finalStatus.result
        assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)

        // Verify error message is propagated
        assertTrue(
            outcome.message.contains("Invalid parameters"),
            "Should include tool's error message"
        )
    }

    // ==================== Helper Functions ====================

    /**
     * Creates a test execution request with default values.
     */
    private fun createTestExecutionRequest(
        instructions: String = "Test instructions",
        taskDescription: String = "Test task",
    ): ExecutionRequest<ExecutionContext> {
        val ticket = Ticket(
            id = "ticket-123",
            description = "Test ticket",
            status = link.socket.ampere.agents.events.tickets.TicketStatus.IN_PROGRESS,
            priority = link.socket.ampere.agents.events.tickets.TicketPriority.MEDIUM,
            category = link.socket.ampere.agents.events.tickets.TicketCategory.FEATURE,
            assignedTo = null,
        )

        val task = Task(
            id = "task-456",
            description = taskDescription,
            status = link.socket.ampere.agents.core.tasks.TaskStatus.IN_PROGRESS,
            createdAt = Clock.System.now(),
            ticketId = ticket.id,
        )

        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = ticket,
            task = task,
            instructions = instructions,
            knowledgeFromPastMemory = emptyList(),
        )

        return ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(),
        )
    }

    // ==================== Mock Implementations ====================

    /**
     * Mock MCP server connection for testing.
     */
    private class MockMcpServerConnection(
        override val serverId: String,
        private val isConnectedValue: Boolean,
    ) : McpServerConnection {

        override val isConnected: Boolean
            get() = isConnectedValue

        var toolCallResult: Result<ToolCallResult> = Result.success(
            ToolCallResult(content = emptyList(), isError = false),
        )

        var onInvokeTool: ((String, JsonElement?) -> Result<ToolCallResult>)? = null

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun initialize(): Result<InitializeResult> {
            return Result.success(
                InitializeResult(
                    protocolVersion = "2024-11-05",
                    serverInfo = ServerInfo(name = "Mock Server", version = "1.0.0"),
                    capabilities = ServerCapabilities(),
                ),
            )
        }

        override suspend fun listTools(): Result<List<McpToolDescriptor>> {
            return Result.success(emptyList())
        }

        override suspend fun invokeTool(
            toolName: String,
            arguments: JsonElement?,
        ): Result<ToolCallResult> {
            return onInvokeTool?.invoke(toolName, arguments) ?: toolCallResult
        }

        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
    }

    /**
     * Mock MCP server manager for testing.
     */
    private class MockMcpServerManager : McpServerManager(
        toolRegistry = MockToolRegistry(),
        eventBus = MockEventBus(),
        eventSource = EventSource.AGENT,
    ) {
        val connections = mutableMapOf<String, McpServerConnection>()

        override suspend fun getConnection(serverId: String): McpServerConnection? {
            return connections[serverId]
        }

        override suspend fun isConnected(serverId: String): Boolean {
            return connections[serverId]?.isConnected == true
        }
    }

    /**
     * Mock tool registry (not used in these tests).
     */
    private class MockToolRegistry : link.socket.ampere.agents.tools.registry.ToolRegistry(
        logger = Logger.withTag("MockToolRegistry"),
    )

    /**
     * Mock event bus (not used in these tests).
     */
    private class MockEventBus : EventSerialBus {
        override suspend fun publish(event: link.socket.ampere.agents.events.Event) {
            // No-op for tests
        }
    }
}
