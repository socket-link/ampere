package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS implementation of HttpClientHandler: [KtorMcpHttpTransport] over the Darwin engine.
 *
 * Uses NSURLSession under the hood, which delivers a response body as it arrives. Its idle
 * timeout (60 seconds without a byte, by default) is raised out of the way, since a quiet
 * event stream or a slow tool call exceeds it; the request's own time limit is the one clock.
 */
actual class HttpClientHandler : McpHttpTransport {

    private val transport = KtorMcpHttpTransport {
        HttpClient(Darwin) {
            engine {
                configureRequest {
                    setTimeoutInterval(IDLE_TIMEOUT_SECONDS)
                }
            }
        }
    }

    actual override suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T> = transport.exchange(request, handle)

    actual override suspend fun close(): Result<Unit> = transport.close()

    private companion object {
        /** A day. Longer than any request's own limit, so that limit is the one that fires. */
        const val IDLE_TIMEOUT_SECONDS = 86_400.0
    }
}
