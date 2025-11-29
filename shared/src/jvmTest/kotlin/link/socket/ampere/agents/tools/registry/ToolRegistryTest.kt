package link.socket.ampere.agents.tools.registry

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.ToolEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for ToolRegistry.
 *
 * These tests validate:
 * - Tool registration and persistence
 * - Tool discovery by autonomy level
 * - Tool discovery by capability
 * - Event emission for tool lifecycle changes
 * - Tool unregistration
 * - Loading persisted tools on startup
 * - Filtering by autonomy level (inclusive)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRegistryTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val json: Json = DEFAULT_JSON
    private val eventSource = EventSource.Agent("test-agent")
    private val eventSerialBusFactory = EventSerialBusFactory(testScope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: ToolRegistryRepository
    private lateinit var eventBus: EventSerialBus
    private lateinit var registry: ToolRegistry

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        repository = ToolRegistryRepository(json, testScope, database)
        eventBus = eventSerialBusFactory.create()
        registry = ToolRegistry(repository, eventBus, eventSource)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Test Helper Functions ====================

    private fun createFunctionTool(
        id: String,
        name: String,
        description: String,
        autonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    ): FunctionTool<ExecutionContext> {
        return FunctionTool<ExecutionContext>(
            id = id,
            name = name,
            description = description,
            requiredAgentAutonomy = autonomy,
            executionFunction = { _: ExecutionRequest<ExecutionContext> ->
                Outcome.Blank(id = "test-outcome")
            }
        )
    }

    private fun createMcpTool(
        id: String,
        name: String,
        description: String,
        serverId: String,
        autonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    ): McpTool {
        return McpTool(
            id = id,
            name = name,
            description = description,
            requiredAgentAutonomy = autonomy,
            serverId = serverId,
            remoteToolName = name,
            inputSchema = null,
        )
    }

    // ==================== Registration Tests ====================

    @Test
    fun `register function tool and mcp tool then retrieve all tools`() {
        runBlocking {
            val functionTool = createFunctionTool(
                id = "func-1",
                name = "Write Code",
                description = "Writes code to a file"
            )
            val mcpTool = createMcpTool(
                id = "mcp-1",
                name = "GitHub PR",
                description = "Creates a GitHub pull request",
                serverId = "github-server"
            )

            registry.registerTool(functionTool).getOrThrow()
            registry.registerTool(mcpTool).getOrThrow()

            val allTools = registry.getAllTools()
            assertEquals(2, allTools.size)

            val functionMetadata = allTools.find { it.id == "func-1" }
            val mcpMetadata = allTools.find { it.id == "mcp-1" }

            assertNotNull(functionMetadata)
            assertTrue(functionMetadata.isFunctionTool())
            assertEquals("Write Code", functionMetadata.name)

            assertNotNull(mcpMetadata)
            assertTrue(mcpMetadata.isMcpTool())
            assertEquals("GitHub PR", mcpMetadata.name)
            assertEquals("github-server", mcpMetadata.mcpServerId)
        }
    }

    @Test
    fun `register tool emits ToolRegistered event with correct metadata`() {
        runBlocking {
            val received = CompletableDeferred<ToolEvent.ToolRegistered>()

            eventBus.subscribe<ToolEvent.ToolRegistered, EventSubscription.ByEventClassType>(
                agentId = "test-subscriber",
                eventClassType = ToolEvent.ToolRegistered.EVENT_CLASS_TYPE,
            ) { event, _ ->
                received.complete(event)
            }

            val tool = createFunctionTool(
                id = "tool-1",
                name = "Test Tool",
                description = "A test tool",
                autonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION
            )

            registry.registerTool(tool).getOrThrow()

            val event = received.await()
            assertEquals("tool-1", event.toolId)
            assertEquals("Test Tool", event.toolName)
            assertEquals(ToolMetadata.TYPE_FUNCTION, event.toolType)
            assertEquals(AgentActionAutonomy.ACT_WITH_NOTIFICATION, event.requiredAutonomy)
            assertNull(event.mcpServerId)
        }
    }

    // ==================== Autonomy Filtering Tests ====================

    @Test
    fun `findToolsByAutonomy returns only tools at or below specified autonomy level`() {
        runBlocking {
            // Create tools with different autonomy levels
            val askBeforeTool = createFunctionTool(
                id = "ask-1",
                name = "Ask Tool",
                description = "Requires asking",
                autonomy = AgentActionAutonomy.ASK_BEFORE_ACTION
            )
            val actWithNotifyTool = createFunctionTool(
                id = "notify-1",
                name = "Notify Tool",
                description = "Acts with notification",
                autonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION
            )
            val fullyAutoTool = createFunctionTool(
                id = "auto-1",
                name = "Auto Tool",
                description = "Fully autonomous",
                autonomy = AgentActionAutonomy.FULLY_AUTONOMOUS
            )
            val selfCorrectTool = createFunctionTool(
                id = "correct-1",
                name = "Self-Correct Tool",
                description = "Self-correcting",
                autonomy = AgentActionAutonomy.SELF_CORRECTING
            )

            registry.registerTool(askBeforeTool).getOrThrow()
            registry.registerTool(actWithNotifyTool).getOrThrow()
            registry.registerTool(fullyAutoTool).getOrThrow()
            registry.registerTool(selfCorrectTool).getOrThrow()

            // Agent with LOW autonomy can only use ASK_BEFORE_ACTION tools
            val lowTools = registry.findToolsByAutonomy(AgentActionAutonomy.ASK_BEFORE_ACTION)
            assertEquals(1, lowTools.size)
            assertEquals("ask-1", lowTools[0].id)

            // Agent with ACT_WITH_NOTIFICATION can use ASK_BEFORE_ACTION and ACT_WITH_NOTIFICATION
            val mediumTools = registry.findToolsByAutonomy(AgentActionAutonomy.ACT_WITH_NOTIFICATION)
            assertEquals(2, mediumTools.size)
            assertTrue(mediumTools.any { it.id == "ask-1" })
            assertTrue(mediumTools.any { it.id == "notify-1" })

            // Agent with FULLY_AUTONOMOUS can use ASK, NOTIFY, and FULLY_AUTONOMOUS
            val highTools = registry.findToolsByAutonomy(AgentActionAutonomy.FULLY_AUTONOMOUS)
            assertEquals(3, highTools.size)
            assertTrue(highTools.any { it.id == "ask-1" })
            assertTrue(highTools.any { it.id == "notify-1" })
            assertTrue(highTools.any { it.id == "auto-1" })

            // Agent with SELF_CORRECTING can use all tools
            val allTools = registry.findToolsByAutonomy(AgentActionAutonomy.SELF_CORRECTING)
            assertEquals(4, allTools.size)
        }
    }

    // ==================== Capability Search Tests ====================

    @Test
    fun `findToolsByCapability returns tools matching name or description`() {
        runBlocking {
            val githubTool = createMcpTool(
                id = "github-1",
                name = "GitHub PR Creator",
                description = "Creates pull requests on GitHub",
                serverId = "github-server"
            )
            val codeTool = createFunctionTool(
                id = "code-1",
                name = "Code Writer",
                description = "Writes code to files"
            )
            val databaseTool = createMcpTool(
                id = "db-1",
                name = "Database Query",
                description = "Queries PostgreSQL databases",
                serverId = "db-server"
            )

            registry.registerTool(githubTool).getOrThrow()
            registry.registerTool(codeTool).getOrThrow()
            registry.registerTool(databaseTool).getOrThrow()

            // Search for "github" - should match name and description
            val githubResults = registry.findToolsByCapability("github")
            assertEquals(1, githubResults.size)
            assertEquals("github-1", githubResults[0].id)

            // Search for "code" - should match both name and description
            val codeResults = registry.findToolsByCapability("code")
            assertEquals(1, codeResults.size)
            assertEquals("code-1", codeResults[0].id)

            // Search for "database" - should match name
            val dbResults = registry.findToolsByCapability("database")
            assertEquals(1, dbResults.size)
            assertEquals("db-1", dbResults[0].id)

            // Search for non-existent capability
            val emptyResults = registry.findToolsByCapability("nonexistent")
            assertEquals(0, emptyResults.size)
        }
    }

    // ==================== Unregistration Tests ====================

    @Test
    fun `unregister tool emits ToolUnregistered event and removes from registry`() {
        runBlocking {
            val tool = createFunctionTool(
                id = "tool-to-remove",
                name = "Temporary Tool",
                description = "This will be removed"
            )

            registry.registerTool(tool).getOrThrow()

            // Verify tool exists
            val beforeRemoval = registry.getTool("tool-to-remove")
            assertNotNull(beforeRemoval)

            // Subscribe to unregister events
            val received = CompletableDeferred<ToolEvent.ToolUnregistered>()
            eventBus.subscribe<ToolEvent.ToolUnregistered, EventSubscription.ByEventClassType>(
                agentId = "test-subscriber",
                eventClassType = ToolEvent.ToolUnregistered.EVENT_CLASS_TYPE,
            ) { event, _ ->
                received.complete(event)
            }

            // Unregister the tool
            registry.unregisterTool("tool-to-remove", "No longer needed").getOrThrow()

            // Verify event was emitted
            val event = received.await()
            assertEquals("tool-to-remove", event.toolId)
            assertEquals("Temporary Tool", event.toolName)
            assertEquals("No longer needed", event.reason)

            // Verify tool is removed
            val afterRemoval = registry.getTool("tool-to-remove")
            assertNull(afterRemoval)
        }
    }

    @Test
    fun `unregister MCP server tools removes all tools for that server`() {
        runBlocking {
            val tool1 = createMcpTool(
                id = "mcp-1",
                name = "Tool 1",
                description = "First tool",
                serverId = "server-x"
            )
            val tool2 = createMcpTool(
                id = "mcp-2",
                name = "Tool 2",
                description = "Second tool",
                serverId = "server-x"
            )
            val tool3 = createMcpTool(
                id = "mcp-3",
                name = "Tool 3",
                description = "Third tool",
                serverId = "server-y"
            )

            registry.registerTool(tool1).getOrThrow()
            registry.registerTool(tool2).getOrThrow()
            registry.registerTool(tool3).getOrThrow()

            // Verify all tools exist
            assertEquals(3, registry.getAllTools().size)

            // Unregister all tools for server-x
            registry.unregisterMcpServerTools("server-x", "Server disconnected").getOrThrow()

            // Verify only server-y tool remains
            val remaining = registry.getAllTools()
            assertEquals(1, remaining.size)
            assertEquals("mcp-3", remaining[0].id)
            assertEquals("server-y", remaining[0].mcpServerId)
        }
    }

    // ==================== Persistence Tests ====================

    @Test
    fun `loadPersistedTools rebuilds cache from database`() {
        runBlocking {
            val tool1 = createFunctionTool(
                id = "persisted-1",
                name = "Persisted Tool 1",
                description = "First persisted tool"
            )
            val tool2 = createMcpTool(
                id = "persisted-2",
                name = "Persisted Tool 2",
                description = "Second persisted tool",
                serverId = "test-server"
            )

            // Register tools
            registry.registerTool(tool1).getOrThrow()
            registry.registerTool(tool2).getOrThrow()

            // Create a fresh registry instance (simulating restart)
            val freshRegistry = ToolRegistry(repository, eventBus, eventSource)

            // Before loading, the new registry should be empty
            assertEquals(0, freshRegistry.getAllTools().size)

            // Load persisted tools
            freshRegistry.loadPersistedTools().getOrThrow()

            // Verify tools were loaded
            val loadedTools = freshRegistry.getAllTools()
            assertEquals(2, loadedTools.size)

            val loaded1 = loadedTools.find { it.id == "persisted-1" }
            val loaded2 = loadedTools.find { it.id == "persisted-2" }

            assertNotNull(loaded1)
            assertEquals("Persisted Tool 1", loaded1.name)

            assertNotNull(loaded2)
            assertEquals("Persisted Tool 2", loaded2.name)
            assertEquals("test-server", loaded2.mcpServerId)
        }
    }

    // ==================== Tool Discovery Complete Event Tests ====================

    @Test
    fun `emitDiscoveryComplete publishes event with correct tool counts`() {
        runBlocking {
            val received = CompletableDeferred<ToolEvent.ToolDiscoveryComplete>()

            eventBus.subscribe<ToolEvent.ToolDiscoveryComplete, EventSubscription.ByEventClassType>(
                agentId = "test-subscriber",
                eventClassType = ToolEvent.ToolDiscoveryComplete.EVENT_CLASS_TYPE,
            ) { event, _ ->
                received.complete(event)
            }

            // Register 2 function tools and 1 MCP tool
            registry.registerTool(createFunctionTool("f1", "Func 1", "desc")).getOrThrow()
            registry.registerTool(createFunctionTool("f2", "Func 2", "desc")).getOrThrow()
            registry.registerTool(createMcpTool("m1", "MCP 1", "desc", "server-1")).getOrThrow()

            // Emit discovery complete
            registry.emitDiscoveryComplete(mcpServerCount = 1)

            val event = received.await()
            assertEquals(3, event.totalToolsDiscovered)
            assertEquals(2, event.functionToolCount)
            assertEquals(1, event.mcpToolCount)
            assertEquals(1, event.mcpServerCount)
        }
    }

    // ==================== Type Filtering Tests ====================

    @Test
    fun `findToolsByType returns only tools of specified type`() {
        runBlocking {
            val functionTool = createFunctionTool("f1", "Function Tool", "desc")
            val mcpTool1 = createMcpTool("m1", "MCP Tool 1", "desc", "server-1")
            val mcpTool2 = createMcpTool("m2", "MCP Tool 2", "desc", "server-1")

            registry.registerTool(functionTool).getOrThrow()
            registry.registerTool(mcpTool1).getOrThrow()
            registry.registerTool(mcpTool2).getOrThrow()

            val functionTools = registry.findToolsByType(ToolMetadata.TYPE_FUNCTION)
            assertEquals(1, functionTools.size)
            assertEquals("f1", functionTools[0].id)

            val mcpTools = registry.findToolsByType(ToolMetadata.TYPE_MCP)
            assertEquals(2, mcpTools.size)
            assertTrue(mcpTools.any { it.id == "m1" })
            assertTrue(mcpTools.any { it.id == "m2" })
        }
    }

    // ==================== MCP Server Filtering Tests ====================

    @Test
    fun `findToolsByMcpServer returns only tools from specified server`() {
        runBlocking {
            val tool1 = createMcpTool("m1", "Tool 1", "desc", "server-a")
            val tool2 = createMcpTool("m2", "Tool 2", "desc", "server-a")
            val tool3 = createMcpTool("m3", "Tool 3", "desc", "server-b")

            registry.registerTool(tool1).getOrThrow()
            registry.registerTool(tool2).getOrThrow()
            registry.registerTool(tool3).getOrThrow()

            val serverATools = registry.findToolsByMcpServer("server-a")
            assertEquals(2, serverATools.size)
            assertTrue(serverATools.any { it.id == "m1" })
            assertTrue(serverATools.any { it.id == "m2" })

            val serverBTools = registry.findToolsByMcpServer("server-b")
            assertEquals(1, serverBTools.size)
            assertEquals("m3", serverBTools[0].id)
        }
    }

    // ==================== Clear Tests ====================

    @Test
    fun `clear removes all tools from registry and database`() {
        runBlocking {
            registry.registerTool(createFunctionTool("f1", "Tool 1", "desc")).getOrThrow()
            registry.registerTool(createMcpTool("m1", "Tool 2", "desc", "server-1")).getOrThrow()

            assertEquals(2, registry.getAllTools().size)

            registry.clear().getOrThrow()

            assertEquals(0, registry.getAllTools().size)

            // Create fresh registry and verify database is empty
            val freshRegistry = ToolRegistry(repository, eventBus, eventSource)
            freshRegistry.loadPersistedTools().getOrThrow()
            assertEquals(0, freshRegistry.getAllTools().size)
        }
    }
}
