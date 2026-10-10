package link.socket.ampere.plug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.ServerManager
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.canon.CanonType
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.link.EgressClass
import link.socket.ampere.link.InMemoryLinkStore
import link.socket.ampere.link.Link
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.LinkRequirement
import link.socket.ampere.link.LinkResolutionService
import link.socket.ampere.link.PlatformTarget
import link.socket.ampere.link.Transport
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.UserGrants

/**
 * The plug execute path, end to end: manifest -> Link resolution -> MCP handshake ->
 * tool discovery -> permission gate -> `tools/call`.
 *
 * Driven through [ToolExecutionEngine] because that is the only execute path (AMPR-401).
 * It used to be driven through `propel/ExecuteStep`, a second entry point nothing in
 * production constructed, while the engine refused every `McpTool` with "MCP tool
 * execution not yet supported".
 */
class PlugContextEndToEndTest {

    private val mcpUri = "mcp://github"
    private val toolName = "list_repos"
    private val plugId = PlugId("github-plug")
    private val linkId = LinkId("github-link")

    private val manifest = PlugManifest(
        id = plugId,
        name = "GitHub Plug",
        version = "1.0.0",
        requiredPermissions = listOf(PlugPermission.MCPServer(mcpUri)),
        mcpServers = listOf(
            McpServerDependency(name = "github", uri = mcpUri),
        ),
        requiredLinks = listOf(
            LinkRequirement(
                name = "github",
                transport = Transport.MCP,
                direction = LinkDirection.READ_WRITE,
                minimumScope = setOf(CanonType.DOCUMENT),
            ),
        ),
        emits = setOf(CanonType.DOCUMENT),
        consumes = setOf(CanonType.DOCUMENT),
    )

    /** A [LinkResolutionService] with a granted Link satisfying the manifest's one requirement. */
    private suspend fun grantedLinkResolutionService(): LinkResolutionService {
        val store = InMemoryLinkStore(
            links = listOf(
                Link(
                    id = linkId,
                    transport = Transport.MCP,
                    direction = LinkDirection.READ_WRITE,
                    egress = EgressClass.ThirdParty("github"),
                    scope = setOf(CanonType.DOCUMENT),
                ),
            ),
        )
        store.grant(plugId, linkId, Instant.fromEpochMilliseconds(0))
        return LinkResolutionService(linkStore = store, platform = PlatformTarget.JVM_DESKTOP)
    }

    @Test
    fun `granted user invokes mcp tool through the engine`() = runTest {
        val expected = ToolCallResult(
            content = listOf(ContentItem(type = "text", text = "ampere")),
            isError = false,
        )
        val mock = recordingConnection(invokeResult = expected)
        val context = plugContext(mock)
        val tool = context.mcpTool()

        // The namespaced "<dependency>:<remote tool>" id is how a plug tool is addressed.
        assertEquals("github:$toolName", tool.id)
        assertEquals(manifest, tool.plugManifest)

        val outcome = engine(
            context = context,
            grants = UserGrants.granted(PlugPermission.MCPServer(mcpUri)),
        ).execute(tool, request())

        val success = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals("ampere", success.message)
        assertEquals(0, llmCalls, "MCP dispatch takes no parameter-generation call")

        val invocation = mock.invocations.single()
        assertEquals(toolName, invocation.first)
        // The envelope McpCallArguments builds from the request, not the tool's own schema.
        assertNotNull(invocation.second)
    }

    @Test
    fun `missing grant denies dispatch and never invokes the connection`() = runTest {
        val mock = recordingConnection()
        val context = plugContext(mock)

        val outcome = engine(context = context, grants = UserGrants())
            .execute(context.mcpTool(), request())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(failure.message.contains("Permission denied"), failure.message)
        assertTrue(failure.message.contains(mcpUri), failure.message)
        assertTrue(mock.invocations.isEmpty())
    }

    @Test
    fun `a closed plug resolves no connection and fails rather than throwing`() = runTest {
        val mock = recordingConnection()
        val context = plugContext(mock)
        val tool = context.mcpTool()
        context.close()

        val outcome = engine(
            context = context,
            grants = UserGrants.granted(PlugPermission.MCPServer(mcpUri)),
        ).execute(tool, request())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(failure.message.contains("not connected"), failure.message)
        assertTrue(mock.invocations.isEmpty())
    }

    @Test
    fun `an engine built without a server manager refuses the plug tool`() = runTest {
        val mock = recordingConnection()
        val context = plugContext(mock)

        val outcome = engine(
            context = context,
            grants = UserGrants.granted(PlugPermission.MCPServer(mcpUri)),
            serverManager = null,
        ).execute(context.mcpTool(), request())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(failure.message.contains("without an MCP ServerManager"), failure.message)
        assertTrue(mock.invocations.isEmpty())
    }

    private var llmCalls = 0

    private val executor = FunctionExecutor.create()

    private suspend fun plugContext(connection: McpServerConnection): PlugContext =
        PlugContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkResolutionService = grantedLinkResolutionService(),
            connectionFactory = { _, _ -> connection },
        ).getOrThrow()

    /** The single MCP tool the manifest's one server exposes. */
    private fun PlugContext.mcpTool(): McpTool =
        availableTools().filterIsInstance<McpTool>().single()

    private fun recordingConnection(
        invokeResult: ToolCallResult = ToolCallResult(),
    ): RecordingMcpConnection = RecordingMcpConnection(
        serverId = mcpUri,
        toolsToReturn = listOf(
            McpToolDescriptor(name = toolName, description = "List repos"),
        ),
        invokeResult = invokeResult,
    )

    private fun engine(
        context: PlugContext,
        grants: UserGrants,
        serverManager: ServerManager? = context.mcpServerManager,
    ): ToolExecutionEngine = ToolExecutionEngine(
        llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = {
                    llmCalls += 1
                    "{}"
                },
            ),
        ),
        executor = executor,
        executorId = executor.id,
        userGrantProvider = { grants },
        mcpServerManager = serverManager,
    )

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        val ticket = Ticket(
            id = "ticket-1",
            title = "List the repositories",
            description = "List the repositories through the GitHub plug",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = "agent-1",
            createdByAgentId = "agent-1",
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "List repos",
        )

        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = executor.id,
                ticket = ticket,
                task = task,
                instructions = "List the repositories",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }
}

private class RecordingMcpConnection(
    override val serverId: String,
    private val toolsToReturn: List<McpToolDescriptor>,
    private val invokeResult: ToolCallResult = ToolCallResult(),
) : McpServerConnection {

    val invocations = mutableListOf<Pair<String, JsonElement?>>()

    override var isConnected: Boolean = false
        private set

    private var initialized = false

    override suspend fun connect(): Result<Unit> {
        isConnected = true
        return Result.success(Unit)
    }

    override suspend fun initialize(): Result<InitializeResult> {
        initialized = true
        return Result.success(
            InitializeResult(
                protocolVersion = "2024-11-05",
                serverInfo = ServerInfo(name = "Mock", version = "0.0.0"),
                capabilities = ServerCapabilities(),
            ),
        )
    }

    override suspend fun listTools(): Result<List<McpToolDescriptor>> =
        if (!initialized) {
            Result.failure(IllegalStateException("not initialized"))
        } else Result.success(toolsToReturn)

    override suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult> {
        invocations += toolName to arguments
        return Result.success(invokeResult)
    }

    override suspend fun disconnect(): Result<Unit> {
        isConnected = false
        initialized = false
        return Result.success(Unit)
    }
}
