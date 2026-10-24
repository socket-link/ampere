package link.socket.ampere.agents.execution.executor.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ACP wire as the spec's own examples write it (AMPR-490): what this client decodes, what
 * it encodes, and that a kind it does not know is a value rather than a failure.
 */
class AcpMessagesTest {

    @Test
    fun `the spec's initialize response decodes with its capabilities and agent info`() {
        val line = """
            {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":true,
            "promptCapabilities":{"image":true,"audio":true,"embeddedContext":true},"mcpCapabilities":{"http":true,"sse":true}},
            "agentInfo":{"name":"my-agent","title":"My Agent","version":"1.0.0"},"authMethods":[]}}
        """.trimIndent().replace("\n", "")

        val message = AcpJson.decodeFromString(JsonRpcMessage.serializer(), line)
        assertTrue(message.isResponse)
        assertFalse(message.isRequest)
        val result = AcpJson.decodeFromJsonElement(InitializeResult.serializer(), message.result!!)

        assertEquals(1, result.protocolVersion)
        assertTrue(result.agentCapabilities.loadSession)
        assertTrue(result.agentCapabilities.promptCapabilities.image)
        assertEquals("my-agent", result.agentInfo?.name)
        assertEquals("1.0.0", result.agentInfo?.version)
    }

    @Test
    fun `a tool call update decodes with its kind status and locations`() {
        val line = """
            {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess_1","update":{"sessionUpdate":"tool_call",
            "toolCallId":"call_001","title":"Editing config","kind":"edit","status":"pending",
            "locations":[{"path":"/home/user/project/config.json","line":12}],
            "content":[{"type":"diff","path":"/home/user/project/config.json","oldText":"a","newText":"b"}],"rawInput":{"x":1}}}}
        """.trimIndent().replace("\n", "")

        val message = AcpJson.decodeFromString(JsonRpcMessage.serializer(), line)
        assertTrue(message.isNotification)
        val params = AcpJson.decodeFromJsonElement(SessionNotificationParams.serializer(), message.params!!)
        val update = assertIs<SessionUpdate.ToolCall>(params.update)

        assertEquals("sess_1", params.sessionId)
        assertEquals("call_001", update.toolCallId)
        assertEquals(ToolKind.EDIT, update.kind)
        assertEquals(ToolCallStatus.PENDING, update.status)
        assertEquals(listOf(ToolCallLocation("/home/user/project/config.json", 12)), update.locations)
        assertEquals("b", update.content.single().newText)
    }

    @Test
    fun `a session update of a kind this client has no type for decodes as Other`() {
        val line = """
            {"sessionId":"s","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hm"}}}
        """.trimIndent()

        val params = AcpJson.decodeFromString(SessionNotificationParams.serializer(), line)

        val other = assertIs<SessionUpdate.Other>(params.update)
        assertEquals("agent_thought_chunk", other.sessionUpdate)
    }

    @Test
    fun `a typed update encodes its discriminator so an agent on the other side can read it`() {
        val encoded = AcpJson.encodeToString(
            SessionNotificationParams.serializer(),
            SessionNotificationParams(
                sessionId = "s",
                update = SessionUpdate.Plan(entries = listOf(PlanEntry(content = "do it", status = "pending"))),
            ),
        )

        assertTrue(encoded.contains("\"sessionUpdate\":\"plan\""), encoded)
        val decoded = AcpJson.decodeFromString(SessionNotificationParams.serializer(), encoded)
        assertEquals("do it", assertIs<SessionUpdate.Plan>(decoded.update).entries.single().content)
    }

    @Test
    fun `a permission request decodes and a selected outcome encodes as the spec writes it`() {
        val line = """
            {"sessionId":"sess_1","toolCall":{"toolCallId":"call_001","kind":"edit","locations":[{"path":"/p/a.kt"}]},
            "options":[{"optionId":"allow-once","name":"Allow","kind":"allow_once"},{"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}
        """.trimIndent().replace("\n", "")

        val params = AcpJson.decodeFromString(RequestPermissionParams.serializer(), line)
        assertEquals("call_001", params.toolCall.toolCallId)
        assertEquals(
            listOf(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.REJECT_ONCE),
            params.options.map { it.kind },
        )

        val selected = AcpJson.encodeToString(
            RequestPermissionResult.serializer(),
            RequestPermissionResult(PermissionOutcome.selected("allow-once")),
        )
        assertEquals("""{"outcome":{"outcome":"selected","optionId":"allow-once"}}""", selected)

        val cancelled = AcpJson.encodeToString(
            RequestPermissionResult.serializer(),
            RequestPermissionResult(PermissionOutcome.cancelled()),
        )
        assertEquals("""{"outcome":{"outcome":"cancelled"}}""", cancelled)
    }

    @Test
    fun `a request encodes jsonrpc first then id method and params and nothing it did not set`() {
        val encoded = AcpJson.encodeToString(
            JsonRpcMessage.serializer(),
            JsonRpcMessage.request(
                id = JsonPrimitive(7),
                method = AcpMethods.SESSION_NEW,
                params = AcpJson.encodeToJsonElement(NewSessionParams.serializer(), NewSessionParams(cwd = "/w")),
            ),
        )

        assertEquals(
            """{"jsonrpc":"2.0","id":7,"method":"session/new","params":{"cwd":"/w","mcpServers":[]}}""",
            encoded,
        )
    }

    @Test
    fun `a notification has a method and no id and a response has an id and no method`() {
        val notification = AcpJson.decodeFromString(
            JsonRpcMessage.serializer(),
            """{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"s"}}""",
        )
        val response = AcpJson.decodeFromString(
            JsonRpcMessage.serializer(),
            """{"jsonrpc":"2.0","id":"abc","error":{"code":-32601,"message":"nope"}}""",
        )

        assertTrue(notification.isNotification)
        assertFalse(notification.isRequest)
        assertTrue(response.isResponse)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error?.code)
        assertEquals("\"abc\"", response.id.toString())
    }

    @Test
    fun `a stop reason this client does not know is kept as text and reads as no known reason`() {
        val known = AcpJson.decodeFromString(PromptResult.serializer(), """{"stopReason":"end_turn"}""")
        val unknown = AcpJson.decodeFromString(PromptResult.serializer(), """{"stopReason":"took_a_nap"}""")

        assertEquals(StopReason.END_TURN, known.reason)
        assertNull(unknown.reason)
        assertEquals("took_a_nap", unknown.stopReason)
    }

    @Test
    fun `fields an agent adds that this client has no type for are ignored`() {
        val element = buildJsonObject {
            put("sessionId", "s")
            put("somethingNew", true)
        }

        val params = AcpJson.decodeFromJsonElement(CancelParams.serializer(), element)

        assertEquals("s", params.sessionId)
    }
}
