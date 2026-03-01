package link.socket.ampere.agents.tools.mcp

import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.McpServerId
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import org.junit.Test

/**
 * Tests for McpToolExecutor - the bridge between McpTool.execute() and MCP server connections.
 *
 * These tests validate:
 * 1. Successful tool execution via mock connection
 * 2. Connection not found error handling
 * 3. MCP server error (isError=true) handling
 * 4. Connection failure (invokeTool returns failure) handling
 * 5. Disconnected server handling
 * 6. Multiple content items in response
 */
class McpToolExecutorTest {

    private fun createTestTicket(): Ticket = Ticket(
        id = generateUUID(),
        title = "Test ticket",
        description = "Test ticket description",
        type = TicketType.TASK,
        priority = TicketPriority.MEDIUM,
        status = TicketStatus.Ready,
        assignedAgentId = null,
        createdByAgentId = "test-agent",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun createTestTask(): Task.CodeChange = Task.CodeChange(
        id = generateUUID(),
        status = TaskStatus.Pending,
        description = "Test task",
    )

    private fun createTestRequest(): ExecutionRequest<ExecutionContext> {
        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = createTestTicket(),
            task = createTestTask(),
            instructions = "Test instructions",
        )
        return ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = false,
            ),
        )
    }

    private fun createTestMcpTool(
        serverId: String = "test-server",
        remoteToolName: String = "test_tool",
    ): McpTool = McpTool(
        id = "$serverId:$remoteToolName",
        name = remoteToolName,
        description = "A test MCP tool",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        serverId = serverId,
        remoteToolName = remoteToolName,
    )

    /**
     * Test 1: Successful tool execution.
     *
     * Validates that a tool invocation routes through the mock connection
     * and returns a Success outcome with the text content.
     */
    @Test
    fun `test successful tool execution returns Success outcome`() = runTest {
        val mockConnection = MockMcpConnection(
            serverId = "test-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "test_tool", description = "Test tool"),
            ),
        )
        // Pre-connect the mock
        mockConnection.connect()
        mockConnection.initialize()

        val serverManager = TestServerManager(
            connections = mapOf("test-server" to mockConnection),
        )

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool()
        val request = createTestRequest()

        val outcome = executor.execute(tool, request)

        assertIs<Outcome.Success>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
    }

    /**
     * Test 2: Connection not found.
     *
     * Validates that executing a tool for an unknown server returns a Failure outcome.
     */
    @Test
    fun `test connection not found returns Failure outcome`() = runTest {
        val serverManager = TestServerManager(connections = emptyMap())

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool(serverId = "nonexistent-server")
        val request = createTestRequest()

        val outcome = executor.execute(tool, request)

        assertIs<Outcome.Failure>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue((outcome as ExecutionOutcome.NoChanges.Failure).message.contains("not connected"))
    }

    /**
     * Test 3: MCP server returns error in ToolCallResult.
     *
     * Validates that isError=true in the result is converted to a Failure outcome.
     */
    @Test
    fun `test MCP tool error response returns Failure outcome`() = runTest {
        val errorConnection = ErrorMcpConnection(
            serverId = "error-server",
            toolCallResult = ToolCallResult(
                content = listOf(ContentItem(type = "text", text = "Permission denied")),
                isError = true,
            ),
        )

        val serverManager = TestServerManager(
            connections = mapOf("error-server" to errorConnection),
        )

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool(serverId = "error-server")
        val request = createTestRequest()

        val outcome = executor.execute(tool, request)

        assertIs<Outcome.Failure>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue((outcome as ExecutionOutcome.NoChanges.Failure).message.contains("Permission denied"))
    }

    /**
     * Test 4: Connection invokeTool returns failure.
     *
     * Validates that transport-level failures are handled gracefully.
     */
    @Test
    fun `test transport failure returns Failure outcome`() = runTest {
        val failingConnection = ErrorMcpConnection(
            serverId = "failing-server",
            invokeFailure = Exception("Network timeout"),
        )

        val serverManager = TestServerManager(
            connections = mapOf("failing-server" to failingConnection),
        )

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool(serverId = "failing-server")
        val request = createTestRequest()

        val outcome = executor.execute(tool, request)

        assertIs<Outcome.Failure>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue((outcome as ExecutionOutcome.NoChanges.Failure).message.contains("Network timeout"))
    }

    /**
     * Test 5: Disconnected server.
     *
     * Validates that a tool targeting a disconnected server returns Failure.
     */
    @Test
    fun `test disconnected server returns Failure outcome`() = runTest {
        val disconnectedConnection = MockMcpConnection(
            serverId = "disconnected-server",
            toolsToReturn = emptyList(),
        )
        // Don't connect - leave isConnected = false

        val serverManager = TestServerManager(
            connections = mapOf("disconnected-server" to disconnectedConnection),
        )

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool(serverId = "disconnected-server")
        val request = createTestRequest()

        val outcome = executor.execute(tool, request)

        assertIs<Outcome.Failure>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue((outcome as ExecutionOutcome.NoChanges.Failure).message.contains("no longer active"))
    }

    /**
     * Test 6: Multiple content items concatenated.
     *
     * Validates that multiple text content items are joined in the response.
     */
    @Test
    fun `test multiple content items are concatenated`() = runTest {
        val multiContentConnection = ErrorMcpConnection(
            serverId = "multi-server",
            toolCallResult = ToolCallResult(
                content = listOf(
                    ContentItem(type = "text", text = "Line 1"),
                    ContentItem(type = "text", text = "Line 2"),
                    ContentItem(type = "image", data = "base64data"), // non-text, should be skipped
                ),
                isError = false,
            ),
        )

        val serverManager = TestServerManager(
            connections = mapOf("multi-server" to multiContentConnection),
        )

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool(serverId = "multi-server")
        val request = createTestRequest()

        val outcome = executor.execute(tool, request)

        assertIs<Outcome.Success>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        val message = (outcome as ExecutionOutcome.NoChanges.Success).message
        assertTrue(message.contains("Line 1"))
        assertTrue(message.contains("Line 2"))
    }

    /**
     * Test 7: McpTool.execute() delegates to executor.
     *
     * Validates that calling McpTool.execute() correctly delegates to the injected executor.
     */
    @Test
    fun `test McpTool execute delegates to executor`() = runTest {
        val mockConnection = MockMcpConnection(
            serverId = "test-server",
            toolsToReturn = emptyList(),
        )
        mockConnection.connect()
        mockConnection.initialize()

        val serverManager = TestServerManager(
            connections = mapOf("test-server" to mockConnection),
        )

        val executor = McpToolExecutor(serverManager = serverManager)
        val tool = createTestMcpTool()
        tool.executor = executor

        val request = createTestRequest()
        val outcome = tool.execute(request)

        assertIs<Outcome.Success>(outcome)
    }

    /**
     * Test 8: McpTool.execute() throws when executor is not set.
     */
    @Test(expected = IllegalStateException::class)
    fun `test McpTool execute throws when executor not set`() = runTest {
        val tool = createTestMcpTool()
        // executor is null by default
        tool.execute(createTestRequest())
    }
}

/**
 * Simple test ServerManager that returns pre-configured connections.
 */
private class TestServerManager(
    private val connections: Map<McpServerId, McpServerConnection>,
) : ServerManager {
    override suspend fun getConnection(serverId: McpServerId): McpServerConnection? =
        connections[serverId]

    override suspend fun isConnected(serverId: McpServerId): Boolean =
        connections[serverId]?.isConnected == true
}

/**
 * Mock connection that returns a configurable ToolCallResult or failure.
 */
private class ErrorMcpConnection(
    override val serverId: String,
    private val toolCallResult: ToolCallResult? = null,
    private val invokeFailure: Exception? = null,
) : McpServerConnection {

    override var isConnected: Boolean = true
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun initialize(): Result<InitializeResult> = Result.success(
        InitializeResult(
            protocolVersion = "2024-11-05",
            serverInfo = ServerInfo(name = "Error Server", version = "1.0.0"),
            capabilities = ServerCapabilities(),
        ),
    )

    override suspend fun listTools(): Result<List<McpToolDescriptor>> = Result.success(emptyList())

    override suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult> {
        if (invokeFailure != null) {
            return Result.failure(invokeFailure)
        }
        return Result.success(toolCallResult ?: ToolCallResult(content = emptyList(), isError = false))
    }

    override suspend fun disconnect(): Result<Unit> {
        isConnected = false
        return Result.success(Unit)
    }
}
