package link.socket.ampere.agents.execution.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * Comprehensive tests for the Tool sealed interface hierarchy.
 *
 * These tests validate:
 * 1. FunctionTool instantiation and execution
 * 2. McpTool instantiation with metadata
 * 3. Sealed interface enforcement (compile-time guarantees)
 * 4. Serialization of both tool types
 * 5. Edge cases (null schemas, etc.)
 */
class ToolHierarchyTest {

    private val now = Clock.System.now()

    private fun createTestTicket(
        id: String = "ticket-1",
        title: String = "Test Ticket",
        description: String = "Test description",
    ): Ticket = Ticket(
        id = id,
        title = title,
        description = description,
        type = TicketType.TASK,
        priority = TicketPriority.MEDIUM,
        status = TicketStatus.InProgress,
        assignedAgentId = "agent-1",
        createdByAgentId = "pm-agent",
        createdAt = now,
        updatedAt = now,
    )

    private fun createTestExecutionRequest(
        context: ExecutionContext = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = createTestTicket(),
            task = Task.blank,
            instructions = "Test instructions",
            knowledgeFromPastMemory = emptyList(),
        ),
    ): ExecutionRequest<ExecutionContext> = ExecutionRequest(
        context = context,
        constraints = ExecutionConstraints(
            timeoutMinutes = 30,
            requireTests = false,
            requireLinting = false,
        ),
    )

    // ==================== FUNCTIONTOOL TESTS ====================

    @Test
    fun `FunctionTool wraps function and returns expected result`() = runTest {
        val expectedOutcome = Outcome.blank
        val functionTool = FunctionTool<ExecutionContext>(
            id = "test-tool",
            name = "Test Tool",
            description = "A test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { expectedOutcome },
        )

        val result = functionTool.execute(createTestExecutionRequest())
        assertEquals(expectedOutcome, result)
    }

    @Test
    fun `FunctionTool execute calls underlying function`() = runTest {
        var functionCalled = false
        val functionTool = FunctionTool<ExecutionContext>(
            id = "test-tool",
            name = "Test Tool",
            description = "A test tool",
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            executionFunction = { request ->
                functionCalled = true
                Outcome.blank
            },
        )

        functionTool.execute(createTestExecutionRequest())
        assertTrue(functionCalled, "Function should have been called")
    }

    @Test
    fun `FunctionTool preserves all properties correctly`() {
        val functionTool = FunctionTool<ExecutionContext>(
            id = "unique-id",
            name = "Unique Tool Name",
            description = "A detailed description",
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            executionFunction = { Outcome.blank },
        )

        assertEquals("unique-id", functionTool.id)
        assertEquals("Unique Tool Name", functionTool.name)
        assertEquals("A detailed description", functionTool.description)
        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, functionTool.requiredAgentAutonomy)
        assertNotNull(functionTool.executionFunction)
    }

    @Test
    fun `FunctionTool can be used in when expression with Tool type`() = runTest {
        val tool: Tool<ExecutionContext> = FunctionTool(
            id = "test",
            name = "Test",
            description = "Test",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { Outcome.blank },
        )

        val result = when (tool) {
            is FunctionTool -> "function"
            is McpTool -> "mcp"
        }

        assertEquals("function", result)
    }

    // ==================== MCPTOOL TESTS ====================

    @Test
    fun `McpTool can be created with all required metadata`() {
        val mcpTool = McpTool(
            id = "github-pr-tool",
            name = "GitHub PR",
            description = "Creates a GitHub pull request",
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            serverId = "github-server-1",
            remoteToolName = "create_pull_request",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("title", "string")
            },
        )

        assertEquals("github-pr-tool", mcpTool.id)
        assertEquals("GitHub PR", mcpTool.name)
        assertEquals("Creates a GitHub pull request", mcpTool.description)
        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, mcpTool.requiredAgentAutonomy)
        assertEquals("github-server-1", mcpTool.serverId)
        assertEquals("create_pull_request", mcpTool.remoteToolName)
        assertNotNull(mcpTool.inputSchema)
    }

    @Test
    fun `McpTool can be created with null inputSchema`() {
        val mcpTool = McpTool(
            id = "simple-tool",
            name = "Simple Tool",
            description = "A tool with no input schema",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "server-1",
            remoteToolName = "simple_action",
            inputSchema = null,
        )

        assertEquals("simple-tool", mcpTool.id)
        assertEquals(null, mcpTool.inputSchema)
    }

    @Test
    fun `McpTool execute throws NotImplementedError`() = runTest {
        val mcpTool = McpTool(
            id = "test-mcp",
            name = "Test MCP",
            description = "Test",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "server-1",
            remoteToolName = "test_tool",
        )

        assertFailsWith<NotImplementedError> {
            mcpTool.execute(createTestExecutionRequest())
        }
    }

    @Test
    fun `McpTool can be used in when expression with Tool type`() {
        val tool: Tool<ExecutionContext> = McpTool(
            id = "test",
            name = "Test",
            description = "Test",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "server-1",
            remoteToolName = "test",
        )

        val result = when (tool) {
            is FunctionTool -> "function"
            is McpTool -> "mcp"
        }

        assertEquals("mcp", result)
    }

    // ==================== SEALED INTERFACE ENFORCEMENT TESTS ====================

    @Test
    fun `when expression on Tool requires both FunctionTool and McpTool cases`() {
        // This test validates compile-time enforcement.
        // If the when expression doesn't handle both cases, the code won't compile.
        val tools: List<Tool<ExecutionContext>> = listOf(
            FunctionTool(
                id = "func",
                name = "Function",
                description = "Func",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
                executionFunction = { Outcome.blank },
            ),
            McpTool(
                id = "mcp",
                name = "MCP",
                description = "MCP",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
                serverId = "server",
                remoteToolName = "tool",
            ),
        )

        val results = tools.map { tool ->
            when (tool) {
                is FunctionTool -> "function"
                is McpTool -> "mcp"
                // If you omit either case, this won't compile due to sealed interface
            }
        }

        assertEquals(listOf("function", "mcp"), results)
    }

    // ==================== SERIALIZATION TESTS ====================

    @Test
    fun `FunctionTool properties can be accessed for serialization`() {
        // Note: FunctionTool contains executable functions which cannot be truly serialized.
        // In practice, tools should be registered in a registry and referenced by ID.
        // This test verifies the properties are accessible, which is what matters for
        // tool discovery and registry persistence.
        val tool = FunctionTool<ExecutionContext>(
            id = "serializable-tool",
            name = "Serializable Tool",
            description = "Can be serialized",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { Outcome.blank },
        )

        // Verify all properties are accessible (these would be stored in registry)
        assertEquals("serializable-tool", tool.id)
        assertEquals("Serializable Tool", tool.name)
        assertEquals("Can be serialized", tool.description)
        assertEquals(AgentActionAutonomy.FULLY_AUTONOMOUS, tool.requiredAgentAutonomy)
        assertNotNull(tool.executionFunction)
    }

    @Test
    fun `McpTool can be serialized and deserialized with round trip`() {
        val original = McpTool(
            id = "github-tool",
            name = "GitHub Tool",
            description = "GitHub integration",
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            serverId = "github-server",
            remoteToolName = "github_action",
            inputSchema = buildJsonObject {
                put("type", "object")
            },
        )

        val json = Json.encodeToString(McpTool.serializer(), original)
        val deserialized = Json.decodeFromString(McpTool.serializer(), json)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.name, deserialized.name)
        assertEquals(original.description, deserialized.description)
        assertEquals(original.requiredAgentAutonomy, deserialized.requiredAgentAutonomy)
        assertEquals(original.serverId, deserialized.serverId)
        assertEquals(original.remoteToolName, deserialized.remoteToolName)
        assertNotNull(deserialized.inputSchema)
    }

    @Test
    fun `McpTool serialization handles null inputSchema correctly`() {
        val original = McpTool(
            id = "simple-tool",
            name = "Simple",
            description = "Simple tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "server-1",
            remoteToolName = "simple",
            inputSchema = null,
        )

        val json = Json.encodeToString(McpTool.serializer(), original)
        val deserialized = Json.decodeFromString(McpTool.serializer(), json)

        assertEquals(original.id, deserialized.id)
        assertEquals(null, deserialized.inputSchema)
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun `FunctionTool with different autonomy levels can be created`() {
        val levels = AgentActionAutonomy.values()

        levels.forEach { level ->
            val tool = FunctionTool<ExecutionContext>(
                id = "tool-${level.name}",
                name = "Tool for ${level.name}",
                description = "Test",
                requiredAgentAutonomy = level,
                executionFunction = { Outcome.blank },
            )

            assertEquals(level, tool.requiredAgentAutonomy)
        }
    }

    @Test
    fun `McpTool with different autonomy levels can be created`() {
        val levels = AgentActionAutonomy.values()

        levels.forEach { level ->
            val tool = McpTool(
                id = "mcp-tool-${level.name}",
                name = "MCP Tool for ${level.name}",
                description = "Test",
                requiredAgentAutonomy = level,
                serverId = "server",
                remoteToolName = "tool",
            )

            assertEquals(level, tool.requiredAgentAutonomy)
        }
    }

    @Test
    fun `Multiple tools can be stored in a collection`() {
        val tools: List<Tool<ExecutionContext>> = listOf(
            FunctionTool(
                id = "tool-1",
                name = "Tool 1",
                description = "First tool",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
                executionFunction = { Outcome.blank },
            ),
            McpTool(
                id = "tool-2",
                name = "Tool 2",
                description = "Second tool",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
                serverId = "server",
                remoteToolName = "tool2",
            ),
            FunctionTool(
                id = "tool-3",
                name = "Tool 3",
                description = "Third tool",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
                executionFunction = { Outcome.blank },
            ),
        )

        assertEquals(3, tools.size)
        assertEquals("tool-1", tools[0].id)
        assertEquals("tool-2", tools[1].id)
        assertEquals("tool-3", tools[2].id)
    }
}
