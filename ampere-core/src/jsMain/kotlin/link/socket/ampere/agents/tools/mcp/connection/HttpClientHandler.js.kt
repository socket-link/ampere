package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * JS implementation of HttpClientHandler: [KtorMcpHttpTransport] over the JS engine.
 *
 * Uses the fetch API under the hood, which has no timeout of its own and delivers a response
 * body as a stream; the request's own time limit is the one clock.
 */
actual class HttpClientHandler : McpHttpTransport {

    private val transport = KtorMcpHttpTransport { HttpClient(Js) }

    actual override suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T> = transport.exchange(request, handle)

    actual override suspend fun close(): Result<Unit> = transport.close()
}
