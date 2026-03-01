package link.socket.ampere.agents.tools.mcp

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.touchlab.kermit.Logger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.registry.ToolRegistry
import link.socket.ampere.agents.tools.registry.ToolRegistryRepository
import link.socket.ampere.db.Database
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration tests for MCP tool lifecycle.
 *
 * Tests the full flow: configure server -> discover tools -> execute tool -> verify events.
 * Uses MockMcpConnection (from McpServerManagerTest) with the connectionFactory parameter
 * to bypass real network connections.
 */
class McpIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var registry: ToolRegistry
    private lateinit var eventBus: EventSerialBus
    private lateinit var mcpManager: McpServerManager
    private val scope = CoroutineScope(Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = Logger.withTag("McpIntegrationTest")

    // Collect emitted events for assertion
    private val emittedEvents = mutableListOf<Event>()

    @Before
    fun setup() {
        // Create in-memory database
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)

        // Create registry
        val repository = ToolRegistryRepository(
            json = json,
            scope = scope,
            database = database,
        )

        eventBus = EventSerialBus(scope = scope)
        val eventSource = EventSource.Agent(agentId = "integration-test")

        registry = ToolRegistry(
            repository = repository,
            eventBus = eventBus,
            eventSource = eventSource,
        )

        // Subscribe to ToolEvents to capture them
        eventBus.subscribe(
            agentId = "test-observer",
            eventType = ToolEvent.ToolRegistered.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                synchronized(emittedEvents) {
                    emittedEvents.add(event)
                }
            },
        )

        eventBus.subscribe(
            agentId = "test-observer",
            eventType = ToolEvent.ToolDiscoveryComplete.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                synchronized(emittedEvents) {
                    emittedEvents.add(event)
                }
            },
        )
    }

    @After
    fun cleanup() = runTest {
        registry.clear()
        mcpManager.disconnectAll()
        driver.close()
    }

    private fun createMcpManagerWithMockConnection(
        mockConnections: Map<String, MockMcpConnection>,
    ): McpServerManager {
        val eventSource = EventSource.Agent(agentId = "integration-test")
        return McpServerManager(
            toolRegistry = registry,
            eventBus = eventBus,
            eventSource = eventSource,
            logger = logger,
            connectionFactory = { config -> mockConnections[config.id]!! },
        ).also { mcpManager = it }
    }

    private fun createTestRequest(): ExecutionRequest<ExecutionContext> {
        val ticket = Ticket(
            id = generateUUID(),
            title = "Integration test ticket",
            description = "Test ticket for integration",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.Ready,
            assignedAgentId = null,
            createdByAgentId = "test-agent",
            createdAt = kotlinx.datetime.Clock.System.now(),
            updatedAt = kotlinx.datetime.Clock.System.now(),
        )

        val task = Task.CodeChange(
            id = generateUUID(),
            status = TaskStatus.Pending,
            description = "Integration test task",
        )

        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = ticket,
            task = task,
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

    /**
     * Test 1: Full lifecycle - configure, discover, verify registry.
     *
     * Validates the complete flow from server configuration through tool discovery
     * to tool availability in the registry.
     */
    @Test
    fun `test full discovery lifecycle registers tools in registry`() = runTest {
        val mockConnection = MockMcpConnection(
            serverId = "github",
            toolsToReturn = listOf(
                McpToolDescriptor(
                    name = "create_issue",
                    description = "Create a GitHub issue",
                ),
                McpToolDescriptor(
                    name = "list_prs",
                    description = "List pull requests",
                ),
            ),
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf("github" to mockConnection),
        )

        // Step 1: Add server configuration
        val config = McpServerConfiguration(
            id = "github",
            displayName = "GitHub MCP Server",
            protocol = McpProtocol.HTTP,
            endpoint = "http://mock:8080",
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
        )
        manager.addServerConfiguration(config)

        // Step 2: Discover tools
        val discoveryResult = manager.discoverAndRegisterTools().getOrThrow()

        // Verify discovery result
        assertEquals(1, discoveryResult.totalServers)
        assertEquals(1, discoveryResult.successfulServers)
        assertEquals(0, discoveryResult.failedServers)
        assertEquals(2, discoveryResult.totalToolsDiscovered)
        assertTrue(discoveryResult.isFullSuccess)

        // Step 3: Verify tools are in the registry
        val allTools = registry.getAllTools()
        assertEquals(2, allTools.size)

        val createIssueTool = allTools.find { it.name == "create_issue" }
        assertNotNull(createIssueTool)
        assertEquals("github:create_issue", createIssueTool.id)
        assertEquals("Create a GitHub issue", createIssueTool.description)
        assertTrue(createIssueTool.isMcpTool())
        assertEquals("github", createIssueTool.mcpServerId)
        assertEquals(AgentActionAutonomy.ACT_WITH_NOTIFICATION, createIssueTool.requiredAgentAutonomy)

        val listPrsTool = allTools.find { it.name == "list_prs" }
        assertNotNull(listPrsTool)
        assertEquals("github:list_prs", listPrsTool.id)
    }

    /**
     * Test 2: Full lifecycle - discover and execute tool.
     *
     * Validates that after discovery, a discovered tool can actually be executed
     * via the standard Tool.execute() interface.
     */
    @Test
    fun `test discovered tool can be executed via Tool execute`() = runTest {
        // Create mock that returns specific content on tool invocation
        val mockConnection = MockMcpConnection(
            serverId = "code-analysis",
            toolsToReturn = listOf(
                McpToolDescriptor(
                    name = "analyze_code",
                    description = "Analyze source code for issues",
                ),
            ),
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf("code-analysis" to mockConnection),
        )

        // Configure and discover
        val config = McpServerConfiguration(
            id = "code-analysis",
            displayName = "Code Analysis Server",
            protocol = McpProtocol.HTTP,
            endpoint = "http://mock:9090",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        )
        manager.addServerConfiguration(config)
        manager.discoverAndRegisterTools().getOrThrow()

        // Verify the connection is active
        assertTrue(manager.isConnected("code-analysis"))

        // The McpTool created by the manager should have its executor set
        // We can verify this by looking up the tool metadata in the registry
        val toolMetadata = registry.getAllTools().first()
        assertEquals("code-analysis:analyze_code", toolMetadata.id)

        // Execute via McpToolExecutor (the same path as Tool.execute())
        val executor = McpToolExecutor(serverManager = manager)
        val mcpTool = link.socket.ampere.agents.execution.tools.McpTool(
            id = "code-analysis:analyze_code",
            name = "analyze_code",
            description = "Analyze source code for issues",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "code-analysis",
            remoteToolName = "analyze_code",
        )
        mcpTool.executor = executor

        val outcome = mcpTool.execute(createTestRequest())
        assertIs<Outcome.Success>(outcome)
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
    }

    /**
     * Test 3: ToolRegistered events are emitted during discovery.
     *
     * Validates that the event bus receives ToolRegistered events for each discovered tool.
     */
    @Test
    fun `test ToolRegistered events emitted during discovery`() = runTest {
        val mockConnection = MockMcpConnection(
            serverId = "events-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "tool_a", description = "Tool A"),
                McpToolDescriptor(name = "tool_b", description = "Tool B"),
            ),
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf("events-server" to mockConnection),
        )

        val config = McpServerConfiguration(
            id = "events-server",
            displayName = "Events Test Server",
            protocol = McpProtocol.HTTP,
            endpoint = "http://mock:7070",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        )
        manager.addServerConfiguration(config)

        // Clear any pre-existing events
        synchronized(emittedEvents) { emittedEvents.clear() }

        manager.discoverAndRegisterTools().getOrThrow()

        // Allow event bus to process async events
        kotlinx.coroutines.delay(200)

        // Verify ToolRegistered events were emitted
        val registeredEvents = synchronized(emittedEvents) {
            emittedEvents.filterIsInstance<ToolEvent.ToolRegistered>()
        }
        assertEquals(2, registeredEvents.size)

        val toolNames = registeredEvents.map { it.toolName }.toSet()
        assertTrue(toolNames.contains("tool_a"))
        assertTrue(toolNames.contains("tool_b"))

        // All should reference the correct server
        registeredEvents.forEach { event ->
            assertEquals("events-server", event.mcpServerId)
            assertEquals("mcp", event.toolType)
        }
    }

    /**
     * Test 4: ToolDiscoveryComplete event emitted after discovery.
     *
     * Validates that a discovery complete event is emitted with correct statistics.
     */
    @Test
    fun `test ToolDiscoveryComplete event emitted after discovery`() = runTest {
        val mockConnection = MockMcpConnection(
            serverId = "disc-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "tool_x", description = "Tool X"),
            ),
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf("disc-server" to mockConnection),
        )

        val config = McpServerConfiguration(
            id = "disc-server",
            displayName = "Discovery Server",
            protocol = McpProtocol.HTTP,
            endpoint = "http://mock:6060",
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        )
        manager.addServerConfiguration(config)

        synchronized(emittedEvents) { emittedEvents.clear() }

        manager.discoverAndRegisterTools().getOrThrow()

        // Allow event bus to process async events
        kotlinx.coroutines.delay(200)

        val discoveryEvents = synchronized(emittedEvents) {
            emittedEvents.filterIsInstance<ToolEvent.ToolDiscoveryComplete>()
        }
        assertTrue(discoveryEvents.isNotEmpty(), "Expected at least one ToolDiscoveryComplete event")

        val event = discoveryEvents.first()
        assertEquals(1, event.mcpToolCount)
        assertEquals(1, event.mcpServerCount)
    }

    /**
     * Test 5: Multi-server discovery with partial failure.
     *
     * Validates that tools from successful servers are registered even when
     * one server fails, and that the registry only contains tools from
     * successful servers.
     */
    @Test
    fun `test multi-server discovery with partial failure`() = runTest {
        val goodConnection = MockMcpConnection(
            serverId = "good-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "good_tool", description = "A working tool"),
            ),
        )

        val badConnection = MockMcpConnection(
            serverId = "bad-server",
            toolsToReturn = emptyList(),
            shouldFailConnection = true,
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf(
                "good-server" to goodConnection,
                "bad-server" to badConnection,
            ),
        )

        manager.addServerConfiguration(
            McpServerConfiguration(
                id = "good-server",
                displayName = "Good Server",
                protocol = McpProtocol.HTTP,
                endpoint = "http://mock:1111",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            ),
        )
        manager.addServerConfiguration(
            McpServerConfiguration(
                id = "bad-server",
                displayName = "Bad Server",
                protocol = McpProtocol.HTTP,
                endpoint = "http://mock:2222",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            ),
        )

        val discoveryResult = manager.discoverAndRegisterTools().getOrThrow()

        // Verify partial success
        assertEquals(2, discoveryResult.totalServers)
        assertEquals(1, discoveryResult.successfulServers)
        assertEquals(1, discoveryResult.failedServers)
        assertTrue(discoveryResult.isPartialSuccess)

        // Verify only the good server's tools are in the registry
        val allTools = registry.getAllTools()
        assertEquals(1, allTools.size)
        assertEquals("good-server:good_tool", allTools.first().id)
    }

    /**
     * Test 6: Tools queryable by autonomy level after discovery.
     *
     * Validates that the ToolRegistry correctly filters discovered tools
     * by agent autonomy level.
     */
    @Test
    fun `test discovered tools queryable by autonomy level`() = runTest {
        val askConnection = MockMcpConnection(
            serverId = "ask-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "safe_tool", description = "A safe tool"),
            ),
        )

        val autoConnection = MockMcpConnection(
            serverId = "auto-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "auto_tool", description = "An autonomous tool"),
            ),
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf(
                "ask-server" to askConnection,
                "auto-server" to autoConnection,
            ),
        )

        manager.addServerConfiguration(
            McpServerConfiguration(
                id = "ask-server",
                displayName = "Ask Server",
                protocol = McpProtocol.HTTP,
                endpoint = "http://mock:3333",
                requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            ),
        )
        manager.addServerConfiguration(
            McpServerConfiguration(
                id = "auto-server",
                displayName = "Auto Server",
                protocol = McpProtocol.HTTP,
                endpoint = "http://mock:4444",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            ),
        )

        manager.discoverAndRegisterTools().getOrThrow()

        // Agent with ASK_BEFORE_ACTION should only see the safe tool
        val askTools = registry.findToolsByAutonomy(AgentActionAutonomy.ASK_BEFORE_ACTION)
        assertEquals(1, askTools.size)
        assertEquals("safe_tool", askTools.first().name)

        // Agent with FULLY_AUTONOMOUS should see both tools
        val autoTools = registry.findToolsByAutonomy(AgentActionAutonomy.FULLY_AUTONOMOUS)
        assertEquals(2, autoTools.size)
    }

    /**
     * Test 7: Disconnect cleans up registry.
     *
     * Validates that disconnecting from servers removes their tools from the registry.
     */
    @Test
    fun `test disconnect removes tools from registry`() = runTest {
        val mockConnection = MockMcpConnection(
            serverId = "cleanup-server",
            toolsToReturn = listOf(
                McpToolDescriptor(name = "temp_tool", description = "A temporary tool"),
            ),
        )

        val manager = createMcpManagerWithMockConnection(
            mapOf("cleanup-server" to mockConnection),
        )

        manager.addServerConfiguration(
            McpServerConfiguration(
                id = "cleanup-server",
                displayName = "Cleanup Server",
                protocol = McpProtocol.HTTP,
                endpoint = "http://mock:5555",
                requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            ),
        )

        // Discover tools
        manager.discoverAndRegisterTools().getOrThrow()
        assertEquals(1, registry.getAllTools().size)

        // Disconnect
        manager.disconnectAll()

        // Allow async events to process
        kotlinx.coroutines.delay(200)

        // Verify tools were removed from registry
        assertEquals(0, registry.getAllTools().size)
    }
}
