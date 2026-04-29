package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.plugin.McpServerDependency

class McpClientTest {

    private val dependency = McpServerDependency(
        name = "github",
        uri = "mcp://github",
    )

    @Test
    fun `listTools returns descriptors from the underlying connection`() = runTest {
        val descriptors = listOf(
            McpToolDescriptor(name = "list_repos", description = "List repos"),
            McpToolDescriptor(name = "open_issue", description = "Open issue"),
        )
        val mock = RecordingMcpConnection(
            serverId = dependency.uri,
            toolsToReturn = descriptors,
        )

        val client = McpClient(
            dependency = dependency,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkId = LinkId("test-link"),
            connectionFactory = { _, _ -> mock },
        )

        client.connect().getOrThrow()
        val tools = client.listTools().getOrThrow()

        assertEquals(descriptors, tools)
    }

    @Test
    fun `callTool round-trips arguments and returns the configured result`() = runTest {
        val expectedResult = ToolCallResult(
            content = listOf(ContentItem(type = "text", text = "ok")),
            isError = false,
        )
        val mock = RecordingMcpConnection(
            serverId = dependency.uri,
            toolsToReturn = emptyList(),
            invokeResult = expectedResult,
        )

        val client = McpClient(
            dependency = dependency,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkId = LinkId("test-link"),
            connectionFactory = { _, _ -> mock },
        )

        client.connect().getOrThrow()

        val args: JsonElement = JsonObject(mapOf("repo" to JsonPrimitive("ampere")))
        val result = client.callTool("list_repos", args).getOrThrow()

        assertEquals(expectedResult, result)
        assertEquals(listOf<Pair<String, JsonElement?>>("list_repos" to args), mock.invocations)
    }

    @Test
    fun `connect surfaces resolved credential into the connection factory`() = runTest {
        val binding = InMemoryMcpCredentialBinding()
        val linkId = LinkId("test-link")
        val credential = McpCredential(authToken = "secret-token")
        binding.bind(linkId, dependency.uri, credential).getOrThrow()

        var capturedCredential: McpCredential? = null
        val mock = RecordingMcpConnection(
            serverId = dependency.uri,
            toolsToReturn = emptyList(),
        )

        val client = McpClient(
            dependency = dependency,
            credentialBinding = binding,
            linkId = linkId,
            connectionFactory = { _, resolved ->
                capturedCredential = resolved
                mock
            },
        )

        client.connect().getOrThrow()

        assertNotNull(capturedCredential)
        assertEquals("secret-token", capturedCredential?.authToken)
    }

    @Test
    fun `listTools fails fast when not connected`() = runTest {
        val client = McpClient(
            dependency = dependency,
            credentialBinding = InMemoryMcpCredentialBinding(),
            linkId = LinkId("test-link"),
            connectionFactory = { _, _ ->
                RecordingMcpConnection(serverId = dependency.uri, toolsToReturn = emptyList())
            },
        )

        val result = client.listTools()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
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
                serverInfo = ServerInfo(name = "Recording", version = "0.0.0"),
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
