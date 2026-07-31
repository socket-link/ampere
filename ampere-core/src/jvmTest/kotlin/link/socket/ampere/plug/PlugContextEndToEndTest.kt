package link.socket.ampere.plug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.canon.CanonType
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
import link.socket.ampere.propel.ExecuteResult
import link.socket.ampere.propel.ExecuteStep

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

        val context = PlugContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkResolutionService = grantedLinkResolutionService(),
            connectionFactory = { _, _ -> mock },
        ).getOrThrow()

        val step = ExecuteStep(
            context = context,
            userGrantProvider = { UserGrants.granted(PlugPermission.MCPServer(mcpUri)) },
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

        val context = PlugContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkResolutionService = grantedLinkResolutionService(),
            connectionFactory = { _, _ -> mock },
        ).getOrThrow()

        val step = ExecuteStep(
            context = context,
            userGrantProvider = { UserGrants() },
        )

        val toolId = "github:$toolName"
        val result = step.execute(toolId, arguments = null)

        val denied = assertIs<ExecuteResult.PermissionDenied>(result)
        assertEquals(PlugPermission.MCPServer(mcpUri), denied.permission)
        assertTrue(mock.invocations.isEmpty())
    }

    @Test
    fun `unknown tool id returns UnknownTool`() = runTest {
        val mock = RecordingMcpConnection(
            serverId = mcpUri,
            toolsToReturn = emptyList(),
        )

        val context = PlugContext.create(
            manifest = manifest,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkResolutionService = grantedLinkResolutionService(),
            connectionFactory = { _, _ -> mock },
        ).getOrThrow()

        val step = ExecuteStep(
            context = context,
            userGrantProvider = { UserGrants.granted(PlugPermission.MCPServer(mcpUri)) },
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
