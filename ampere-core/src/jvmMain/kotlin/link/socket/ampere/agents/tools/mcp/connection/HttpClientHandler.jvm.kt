package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * JVM implementation of HttpClientHandler: [KtorMcpHttpTransport] over the OkHttp engine.
 *
 * OkHttp's own timeouts are switched off (zero means none). Its defaults would give up on a
 * response after ten seconds without a byte, which a quiet event stream or a slow tool call
 * exceeds; the request's own time limit is the one clock.
 */
actual class HttpClientHandler : McpHttpTransport {

    private val transport = KtorMcpHttpTransport {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(0, TimeUnit.MILLISECONDS)
                    readTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(0, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    actual override suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T> = transport.exchange(request, handle)

    actual override suspend fun close(): Result<Unit> = transport.close()
}
