package link.socket.ampere.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.mcp.LinkId
import link.socket.ampere.plugin.permission.PluginPermission
import link.socket.ampere.plugin.permission.UserGrants
import link.socket.ampere.propel.ExecuteResult
import link.socket.ampere.propel.ExecuteStep

class PluginContextEndToEndTest {

    private val mcpUri = "mcp://github"
    private val toolName = "list_repos"

    private val manifest = PluginManifest(
        id = "github-plugin",
        name = "GitHub Plugin",
        version = "1.0.0",
        requiredPermissions = listOf(PluginPermission.MCPServer(mcpUri)),
        mcpServers = listOf(
            McpServerDependency(name = "github", uri = mcpUri),
        ),
    )

    @Test
    fun `granted user invokes mcp tool successfully`() = runTest {
        val expected = ToolCallResult(
            content = listOf(ContentItem(type = "text", text = "ampere")),
            isError = false,
        )
        val mock = RecordingMcpConnection(
            serverId = mcpUri,
            toolsToReturn = listOf(
                McpToolDescriptor(name = toolName, description = "List repos"),
            ),
            invokeResult = expected,
        )

        val context = PluginContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkId = LinkId("test-link"),
            connectionFactory = { _, _ -> mock },
        ).getOrThrow()

        val step = ExecuteStep(
            context = context,
            userGrantProvider = { UserGrants.granted(PluginPermission.MCPServer(mcpUri)) },
        )

        val toolId = "github:$toolName"
        val result = step.execute(toolId, arguments = null)

        val success = assertIs<ExecuteResult.Success>(result)
        assertEquals(expected, success.result)
        assertEquals(1, mock.invocations.size)
        assertEquals(toolName, mock.invocations.single().first)
    }

    @Test
    fun `missing grant denies dispatch and never invokes the connection`() = runTest {
        val mock = RecordingMcpConnection(
            serverId = mcpUri,
            toolsToReturn = listOf(
                McpToolDescriptor(name = toolName, description = "List repos"),
            ),
        )

        val context = PluginContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkId = LinkId("test-link"),
            connectionFactory = { _, _ -> mock },
        ).getOrThrow()

        val step = ExecuteStep(
            context = context,
            userGrantProvider = { UserGrants() },
        )

        val toolId = "github:$toolName"
        val result = step.execute(toolId, arguments = null)

        val denied = assertIs<ExecuteResult.PermissionDenied>(result)
        assertEquals(PluginPermission.MCPServer(mcpUri), denied.permission)
        assertTrue(mock.invocations.isEmpty())
    }

    @Test
    fun `unknown tool id returns UnknownTool`() = runTest {
        val mock = RecordingMcpConnection(
            serverId = mcpUri,
            toolsToReturn = emptyList(),
        )

        val context = PluginContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkId = LinkId("test-link"),
            connectionFactory = { _, _ -> mock },
        ).getOrThrow()

        val step = ExecuteStep(
            context = context,
            userGrantProvider = { UserGrants.granted(PluginPermission.MCPServer(mcpUri)) },
        )

        val result = step.execute("github:does_not_exist", arguments = null)

        assertIs<ExecuteResult.UnknownTool>(result)
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
