package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import link.socket.ampere.util.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * JS implementation of HttpClientHandler using Ktor with JS engine.
 *
 * Uses fetch API under the hood for HTTP communication in browser environments.
 */
actual class HttpClientHandler {
    private var httpClient: HttpClient? = null

    actual suspend fun validateEndpoint(
        url: String,
        authToken: String?,
        timeoutMs: Long,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            // Create HTTP client if not already created
            if (httpClient == null) {
                httpClient = HttpClient(Js)
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
    ): Result<String> = withContext(ioDispatcher) {
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

    actual suspend fun close(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            httpClient?.close()
            httpClient = null
        }
    }
}
