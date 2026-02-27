package link.socket.ampere.config

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.tools.mcp.McpProtocol
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for MCP server configuration parsing, validation, and conversion.
 */
class McpConfigTest {

    /**
     * Test parsing a full MCP configuration from YAML.
     */
    @Test
    fun `test parse MCP config from YAML`() {
        val yaml = """
            ai:
              provider: anthropic
              model: sonnet-4
            team:
              - role: engineer
            mcp:
              servers:
                - id: github
                  name: GitHub CLI
                  protocol: stdio
                  endpoint: /usr/local/bin/github-mcp-server
                  autonomy: act-with-notification
                - id: database
                  name: Database Service
                  protocol: http
                  endpoint: https://db.example.com/api
                  auth-token: secret-token
                  autonomy: ask-before-action
                  timeout-ms: 60000
                  auto-reconnect: true
        """.trimIndent()

        val config = ConfigParser.parse(yaml)

        assertNotNull(config.mcp)
        assertEquals(2, config.mcp!!.servers.size)

        val github = config.mcp!!.servers[0]
        assertEquals("github", github.id)
        assertEquals("GitHub CLI", github.name)
        assertEquals("stdio", github.protocol)
        assertEquals("/usr/local/bin/github-mcp-server", github.endpoint)
        assertNull(github.authToken)
        assertEquals("act-with-notification", github.autonomy)

        val database = config.mcp!!.servers[1]
        assertEquals("database", database.id)
        assertEquals("Database Service", database.name)
        assertEquals("http", database.protocol)
        assertEquals("https://db.example.com/api", database.endpoint)
        assertEquals("secret-token", database.authToken)
        assertEquals("ask-before-action", database.autonomy)
        assertEquals(60000L, database.timeoutMs)
        assertEquals(true, database.autoReconnect)
    }

    /**
     * Test config without MCP section parses correctly.
     */
    @Test
    fun `test parse config without MCP section`() {
        val yaml = """
            ai:
              provider: anthropic
              model: sonnet-4
            team:
              - role: engineer
        """.trimIndent()

        val config = ConfigParser.parse(yaml)
        assertNull(config.mcp)
    }

    /**
     * Test MCP config defaults.
     */
    @Test
    fun `test MCP server config defaults`() {
        val yaml = """
            ai:
              provider: anthropic
              model: sonnet-4
            team:
              - role: engineer
            mcp:
              servers:
                - id: minimal
                  name: Minimal Server
                  protocol: http
                  endpoint: http://localhost:8080
        """.trimIndent()

        val config = ConfigParser.parse(yaml)
        val server = config.mcp!!.servers[0]

        assertEquals("act-with-notification", server.autonomy)
        assertEquals(30000L, server.timeoutMs)
        assertEquals(false, server.autoReconnect)
        assertNull(server.authToken)
    }

    /**
     * Test conversion to McpServerConfiguration objects.
     */
    @Test
    fun `test convert MCP config to McpServerConfiguration`() {
        val mcpConfig = McpServersConfig(
            servers = listOf(
                McpServerConfigYaml(
                    id = "github",
                    name = "GitHub CLI",
                    protocol = "stdio",
                    endpoint = "/usr/local/bin/github-mcp-server",
                    autonomy = "fully-autonomous",
                ),
                McpServerConfigYaml(
                    id = "database",
                    name = "Database",
                    protocol = "http",
                    endpoint = "https://db.example.com/api",
                    authToken = "token123",
                    autonomy = "ask-before-action",
                    timeoutMs = 60000,
                    autoReconnect = true,
                ),
            ),
        )

        val configs = ConfigConverter.toMcpServerConfigurations(mcpConfig)

        assertEquals(2, configs.size)

        val github = configs[0]
        assertEquals("github", github.id)
        assertEquals("GitHub CLI", github.displayName)
        assertEquals(McpProtocol.STDIO, github.protocol)
        assertEquals("/usr/local/bin/github-mcp-server", github.endpoint)
        assertNull(github.authToken)
        assertEquals(AgentActionAutonomy.FULLY_AUTONOMOUS, github.requiredAgentAutonomy)

        val database = configs[1]
        assertEquals("database", database.id)
        assertEquals("Database", database.displayName)
        assertEquals(McpProtocol.HTTP, database.protocol)
        assertEquals("https://db.example.com/api", database.endpoint)
        assertEquals("token123", database.authToken)
        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, database.requiredAgentAutonomy)
        assertEquals(60000L, database.timeoutMs)
        assertEquals(true, database.autoReconnect)
    }

    /**
     * Test null MCP config returns empty list.
     */
    @Test
    fun `test convert null MCP config returns empty list`() {
        val configs = ConfigConverter.toMcpServerConfigurations(null)
        assertTrue(configs.isEmpty())
    }

    /**
     * Test protocol conversion.
     */
    @Test
    fun `test protocol string conversion`() {
        assertEquals(McpProtocol.STDIO, ConfigConverter.toMcpProtocol("stdio"))
        assertEquals(McpProtocol.HTTP, ConfigConverter.toMcpProtocol("http"))
        assertEquals(McpProtocol.SSE, ConfigConverter.toMcpProtocol("sse"))
        assertEquals(McpProtocol.HTTP, ConfigConverter.toMcpProtocol("HTTP"))
    }

    /**
     * Test autonomy conversion.
     */
    @Test
    fun `test autonomy string conversion`() {
        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, ConfigConverter.toAgentAutonomy("ask-before-action"))
        assertEquals(AgentActionAutonomy.ACT_WITH_NOTIFICATION, ConfigConverter.toAgentAutonomy("act-with-notification"))
        assertEquals(AgentActionAutonomy.FULLY_AUTONOMOUS, ConfigConverter.toAgentAutonomy("fully-autonomous"))
        assertEquals(AgentActionAutonomy.SELF_CORRECTING, ConfigConverter.toAgentAutonomy("self-correcting"))
    }

    /**
     * Test invalid protocol throws.
     */
    @Test
    fun `test invalid protocol throws`() {
        assertThrows<IllegalArgumentException> {
            ConfigConverter.toMcpProtocol("invalid")
        }
    }

    /**
     * Test invalid autonomy throws.
     */
    @Test
    fun `test invalid autonomy throws`() {
        assertThrows<IllegalArgumentException> {
            ConfigConverter.toAgentAutonomy("invalid")
        }
    }

    /**
     * Test SSE protocol conversion.
     */
    @Test
    fun `test SSE protocol converted correctly`() {
        val mcpConfig = McpServersConfig(
            servers = listOf(
                McpServerConfigYaml(
                    id = "streaming",
                    name = "Streaming Server",
                    protocol = "sse",
                    endpoint = "https://stream.example.com/mcp",
                    autonomy = "self-correcting",
                ),
            ),
        )

        val configs = ConfigConverter.toMcpServerConfigurations(mcpConfig)
        assertEquals(McpProtocol.SSE, configs[0].protocol)
        assertEquals(AgentActionAutonomy.SELF_CORRECTING, configs[0].requiredAgentAutonomy)
    }
}
