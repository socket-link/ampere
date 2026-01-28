package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of HttpClientHandler using Ktor.
 *
 * Uses OkHttp engine for HTTP communication.
 */
actual class HttpClientHandler {
    private var httpClient: HttpClient? = null

    actual suspend fun validateEndpoint(
        url: String,
        authToken: String?,
        timeoutMs: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Create HTTP client if not already created
            if (httpClient == null) {
                httpClient = HttpClient(OkHttp) {
                    engine {
                        config {
                            connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        }
                    }
                }
            }

            // Send a simple GET request to validate accessibility
            val response: HttpResponse = httpClient!!.get(url) {
                if (authToken != null) {
                    header(HttpHeaders.Authorization, "Bearer $authToken")
                }
            }

            // Check for successful response
            if (!response.status.isSuccess()) {
                throw McpConnectionException(
                    "Endpoint validation failed: ${response.status.value} ${response.status.description}",
                )
            }
        }
    }

    actual suspend fun sendRequest(
        url: String,
        body: String,
        authToken: String?,
        timeoutMs: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = httpClient ?: throw McpConnectionException("Client not initialized")

            // Send POST request with JSON body
            val response: HttpResponse = client.post(url) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                if (authToken != null) {
                    header(HttpHeaders.Authorization, "Bearer $authToken")
                }
                setBody(body)
            }

            // Check for successful response
            if (!response.status.isSuccess()) {
                throw McpConnectionException(
                    "HTTP request failed: ${response.status.value} ${response.status.description}",
                )
            }

            // Return response body
            response.bodyAsText()
        }
    }

    actual suspend fun close(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient?.close()
            httpClient = null
        }
    }
}
