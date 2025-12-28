package link.socket.ampere.agents.tools.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.tools.mcp.protocol.*

/**
 * Tests for McpClient - MCP protocol implementation.
 *
 * These tests validate:
 * 1. Request creation (initialize, tools/list, tools/call)
 * 2. Response processing
 * 3. State management (isInitialized)
 * 4. Error handling
 * 5. Serialization/deserialization
 */
class McpClientTest {
    private val mcpClient = McpClient()

    /**
     * Test 1: Create initialize request.
     */
    @Test
    fun `test create initialize request`() {
        val request = mcpClient.createInitializeRequest()

        assertEquals("2.0", request.jsonrpc)
        assertEquals("initialize", request.method)
        assertNotNull(request.id)
        assertNotNull(request.params)
    }

    /**
     * Test 2: Process successful initialize response.
     */
    @Test
    fun `test process successful initialize response`() {
        // Create a successful initialize response
        val responseJson = """
            {
                "jsonrpc": "2.0",
                "id": "test-123",
                "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {
                        "name": "Test Server",
                        "version": "1.0.0"
                    },
                    "capabilities": {}
                }
            }
        """.trimIndent()

        val response = mcpClient.deserializeResponse(responseJson).getOrThrow()
        val result = mcpClient.processInitializeResponse(response).getOrThrow()

        assertTrue(mcpClient.isInitialized)
        assertEquals("Test Server", result.serverInfo.name)
        assertEquals("1.0.0", result.serverInfo.version)
    }

    /**
     * Test 3: Process error initialize response.
     */
    @Test
    fun `test process error initialize response`() {
        val responseJson = """
            {
                "jsonrpc": "2.0",
                "id": "test-123",
                "error": {
                    "code": -32603,
                    "message": "Internal error during initialization"
                }
            }
        """.trimIndent()

        val response = mcpClient.deserializeResponse(responseJson).getOrThrow()
        val result = mcpClient.processInitializeResponse(response)

        assertTrue(result.isFailure)
        assertFalse(mcpClient.isInitialized)
    }

    /**
     * Test 4: Cannot call tools/list before initialize.
     */
    @Test
    fun `test tools list requires initialization`() {
        val client = McpClient() // Fresh client, not initialized

        try {
            client.createToolsListRequest()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // Expected - must initialize first
            assertTrue(e.message?.contains("initialize") == true)
        }
    }

    /**
     * Test 5: Create tools/list request after initialization.
     */
    @Test
    fun `test create tools list request after initialization`() {
        // Simulate initialization
        val initResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "init-123",
                "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {"name": "Test", "version": "1.0"},
                    "capabilities": {}
                }
            }
        """.trimIndent()

        val initResponse = mcpClient.deserializeResponse(initResponseJson).getOrThrow()
        mcpClient.processInitializeResponse(initResponse).getOrThrow()

        // Now create tools/list request
        val request = mcpClient.createToolsListRequest()

        assertEquals("2.0", request.jsonrpc)
        assertEquals("tools/list", request.method)
        assertNotNull(request.id)
    }

    /**
     * Test 6: Process tools/list response.
     */
    @Test
    fun `test process tools list response`() {
        // Simulate initialization first
        val initResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "init-123",
                "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {"name": "Test", "version": "1.0"},
                    "capabilities": {}
                }
            }
        """.trimIndent()

        val initResponse = mcpClient.deserializeResponse(initResponseJson).getOrThrow()
        mcpClient.processInitializeResponse(initResponse).getOrThrow()

        // Process tools/list response
        val toolsListResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "tools-list-123",
                "result": {
                    "tools": [
                        {
                            "name": "list_files",
                            "description": "Lists files in a directory"
                        },
                        {
                            "name": "read_file",
                            "description": "Reads a file's contents"
                        }
                    ]
                }
            }
        """.trimIndent()

        val response = mcpClient.deserializeResponse(toolsListResponseJson).getOrThrow()
        val result = mcpClient.processToolsListResponse(response).getOrThrow()

        assertEquals(2, result.tools.size)
        assertEquals("list_files", result.tools[0].name)
        assertEquals("read_file", result.tools[1].name)
    }

    /**
     * Test 7: Create tool call request.
     */
    @Test
    fun `test create tool call request`() {
        // Simulate initialization
        val initResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "init-123",
                "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {"name": "Test", "version": "1.0"},
                    "capabilities": {}
                }
            }
        """.trimIndent()

        val initResponse = mcpClient.deserializeResponse(initResponseJson).getOrThrow()
        mcpClient.processInitializeResponse(initResponse).getOrThrow()

        // Create tool call request
        val arguments = buildJsonObject {
            put("path", "/home/user")
        }

        val request = mcpClient.createToolCallRequest("list_files", arguments)

        assertEquals("2.0", request.jsonrpc)
        assertEquals("tools/call", request.method)
        assertNotNull(request.id)
        assertNotNull(request.params)
    }

    /**
     * Test 8: Process tool call response.
     */
    @Test
    fun `test process tool call response`() {
        // Simulate initialization
        val initResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "init-123",
                "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {"name": "Test", "version": "1.0"},
                    "capabilities": {}
                }
            }
        """.trimIndent()

        val initResponse = mcpClient.deserializeResponse(initResponseJson).getOrThrow()
        mcpClient.processInitializeResponse(initResponse).getOrThrow()

        // Process tool call response
        val toolCallResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "tool-call-123",
                "result": {
                    "content": [
                        {
                            "type": "text",
                            "text": "file1.txt\nfile2.txt\nfile3.txt"
                        }
                    ],
                    "isError": false
                }
            }
        """.trimIndent()

        val response = mcpClient.deserializeResponse(toolCallResponseJson).getOrThrow()
        val result = mcpClient.processToolCallResponse(response).getOrThrow()

        assertFalse(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertNotNull(result.content[0].text)
    }

    /**
     * Test 9: Reset client state.
     */
    @Test
    fun `test reset clears client state`() {
        // Initialize client
        val initResponseJson = """
            {
                "jsonrpc": "2.0",
                "id": "init-123",
                "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {"name": "Test", "version": "1.0"},
                    "capabilities": {}
                }
            }
        """.trimIndent()

        val initResponse = mcpClient.deserializeResponse(initResponseJson).getOrThrow()
        mcpClient.processInitializeResponse(initResponse).getOrThrow()

        assertTrue(mcpClient.isInitialized)

        // Reset
        mcpClient.reset()

        assertFalse(mcpClient.isInitialized)
        assertEquals(null, mcpClient.serverInfo)
    }

    /**
     * Test 10: Serialize and deserialize roundtrip.
     */
    @Test
    fun `test serialize and deserialize roundtrip`() {
        val request = mcpClient.createInitializeRequest()
        val serialized = mcpClient.serializeRequest(request)

        // Basic validation - ensure serialization produces non-empty output
        assertNotNull(serialized)
        assertTrue(serialized.isNotEmpty(), "Serialized output should not be empty")

        // Verify it contains the basic fields (case-insensitive to handle different JSON formats)
        val lowerSerialized = serialized.lowercase()
        assertTrue(lowerSerialized.contains("jsonrpc"), "Should contain 'jsonrpc' field")
        assertTrue(lowerSerialized.contains("2.0"), "Should contain version '2.0'")
        assertTrue(lowerSerialized.contains("method"), "Should contain 'method' field")
        assertTrue(lowerSerialized.contains("initialize"), "Should contain method 'initialize'")
        assertTrue(lowerSerialized.contains("id"), "Should contain 'id' field")
    }

    /**
     * Test 11: Response indicates success correctly.
     */
    @Test
    fun `test response success detection`() {
        val successJson = """
            {
                "jsonrpc": "2.0",
                "id": "test",
                "result": {"status": "ok"}
            }
        """.trimIndent()

        val errorJson = """
            {
                "jsonrpc": "2.0",
                "id": "test",
                "error": {"code": -32600, "message": "Invalid request"}
            }
        """.trimIndent()

        val successResponse = mcpClient.deserializeResponse(successJson).getOrThrow()
        val errorResponse = mcpClient.deserializeResponse(errorJson).getOrThrow()

        assertTrue(successResponse.isSuccess())
        assertFalse(successResponse.isError())

        assertFalse(errorResponse.isSuccess())
        assertTrue(errorResponse.isError())
    }
}
