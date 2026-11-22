package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * [KtorMcpHttpTransport] over a mock engine: what it hands Ktor, and what it
 * hands back (AMPR-514). What a real engine adds or strips is the JVM fixture
 * test's question; this one runs on every platform.
 *
 * Bodies run on [Dispatchers.Default], off `runTest`'s virtual clock. The mock
 * engine answers on a dispatcher of its own, so to the test scheduler the wait
 * for it looks like idleness, and virtual time would jump straight past the
 * exchange's time limit before the answer arrived.
 */
class KtorMcpHttpTransportTest {

    private class Sent(val request: HttpRequestData, val body: String)

    private fun onRealClock(body: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { body() }
    }

    private val sent = mutableListOf<Sent>()

    private fun transport(
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
        body: String = "{}",
    ) = KtorMcpHttpTransport {
        HttpClient(
            MockEngine { call ->
                sent += Sent(call, call.body.toByteArray().decodeToString())
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(*headers.map { (name, value) -> name to listOf(value) }.toTypedArray()),
                )
            },
        )
    }

    private fun post(timeoutMs: Long = 30_000) = McpHttpRequest(
        method = McpHttpMethod.POST,
        url = "https://mcp.example.test/mcp",
        headers = mapOf(
            "Accept" to "application/json, text/event-stream",
            "Authorization" to "Bearer fake-token",
            "Mcp-Session-Id" to "fixture-session-1",
        ),
        body = """{"jsonrpc":"2.0","id":"1","method":"tools/list"}""",
        timeoutMs = timeoutMs,
    )

    @Test
    fun `a post carries its headers and its body as JSON`() = onRealClock {
        transport().exchange(post()) { }.getOrThrow()

        val request = sent.single()
        assertEquals(HttpMethod.Post, request.request.method)
        assertEquals("https://mcp.example.test/mcp", request.request.url.toString())
        assertEquals("application/json, text/event-stream", request.request.headers["Accept"])
        assertEquals("Bearer fake-token", request.request.headers["Authorization"])
        assertEquals("fixture-session-1", request.request.headers["Mcp-Session-Id"])
        assertEquals(ContentType.Application.Json, request.request.body.contentType?.withoutParameters())
        assertEquals("""{"jsonrpc":"2.0","id":"1","method":"tools/list"}""", request.body)
    }

    @Test
    fun `a delete carries its headers and no body`() = onRealClock {
        val delete = McpHttpRequest(
            method = McpHttpMethod.DELETE,
            url = "https://mcp.example.test/mcp",
            headers = mapOf("Mcp-Session-Id" to "fixture-session-1"),
            timeoutMs = 5_000,
        )

        val status = transport(status = HttpStatusCode.NoContent, headers = emptyMap(), body = "")
            .exchange(delete) { response -> response.status }
            .getOrThrow()

        assertEquals(204, status)
        val request = sent.single()
        assertEquals(HttpMethod.Delete, request.request.method)
        assertEquals("fixture-session-1", request.request.headers["Mcp-Session-Id"])
        assertEquals("", request.body)
    }

    @Test
    fun `response headers are found whatever their case`() = onRealClock {
        val transport = transport(
            headers = mapOf(
                "Content-Type" to "Text/Event-Stream; charset=utf-8",
                "Mcp-Session-Id" to "fixture-session-1",
            ),
        )

        val seen = transport.exchange(post()) { response ->
            Triple(response.header("mcp-session-id"), response.header("MCP-SESSION-ID"), response.mediaType)
        }.getOrThrow()

        assertEquals(Triple("fixture-session-1", "fixture-session-1", "text/event-stream"), seen)
    }

    @Test
    fun `a body is read a line at a time and then ends`() = onRealClock {
        val transport = transport(
            headers = mapOf("Content-Type" to "text/event-stream"),
            body = "event: message\ndata: one\n\ndata: two\n\n",
        )

        val lines = transport.exchange(post()) { response ->
            buildList {
                while (true) add(response.body.readLine() ?: break)
            }
        }.getOrThrow()

        assertEquals(listOf("event: message", "data: one", "", "data: two", ""), lines)
    }

    @Test
    fun `a body is read whole as text`() = onRealClock {
        val text = transport(body = """{"jsonrpc":"2.0","id":"1","result":{}}""")
            .exchange(post()) { response -> response.body.readText() }
            .getOrThrow()

        assertEquals("""{"jsonrpc":"2.0","id":"1","result":{}}""", text)
    }

    @Test
    fun `any status is a response and reaches the handler`() = onRealClock {
        val transport = transport(
            status = HttpStatusCode.Unauthorized,
            headers = mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"https://mcp.example.test/meta\""),
            body = "",
        )

        val seen = transport.exchange(post()) { response -> response.status to response.header("WWW-Authenticate") }
            .getOrThrow()

        assertEquals(401 to "Bearer resource_metadata=\"https://mcp.example.test/meta\"", seen)
    }

    @Test
    fun `a media type is null when the response declares none`() = onRealClock {
        val mediaType = transport(status = HttpStatusCode.Accepted, headers = emptyMap(), body = "")
            .exchange(post()) { response -> response.mediaType }
            .getOrThrow()

        assertNull(mediaType)
    }

    @Test
    fun `what the handler throws is the failure`() = onRealClock {
        val thrown = McpConnectionException("the handler did not like it")

        val failure = transport().exchange<Unit>(post()) { throw thrown }.exceptionOrNull()

        assertSame(thrown, failure)
    }

    @Test
    fun `an exchange that outlives its limit is a request timeout`() = onRealClock {
        val transport = KtorMcpHttpTransport { HttpClient(MockEngine { awaitCancellation() }) }

        val failure = transport.exchange(post(timeoutMs = 200)) { }.exceptionOrNull()

        assertEquals(200L, assertIs<McpRequestTimeoutException>(failure).timeoutMs)
    }

    @Test
    fun `a request description names its headers and none of their values`() {
        val described = post().toString()

        assertFalse("fake-token" in described, described)
        assertFalse("fixture-session-1" in described, described)
        assertEquals(
            "McpHttpRequest(method=POST, url=https://mcp.example.test/mcp, " +
                "headers=[Accept, Authorization, Mcp-Session-Id], body=${post().body!!.length} chars, timeoutMs=30000)",
            described,
        )
    }
}
