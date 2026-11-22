package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One HTTP exchange at a time, with the response handed over while its body is
 * still arriving (AMPR-514).
 *
 * This is the seam [HttpMcpConnection] sends through. It exists as an interface
 * so the connection's protocol logic can be tested against scripted responses;
 * production uses the platform [HttpClientHandler].
 *
 * The response is only valid inside `handle`: the body is a live stream, and
 * the exchange is released when `handle` returns. That is what lets a
 * `text/event-stream` body be read event by event, and abandoned as soon as
 * the message the caller wanted has arrived.
 */
interface McpHttpTransport {

    /**
     * Send [request] and run [handle] on the response as soon as its status and
     * headers are in, before the body has been read.
     *
     * Any status is a response: a 401 or a 404 reaches [handle] like a 200.
     * The result is a failure when no response arrived, when [handle] threw, or
     * when the whole exchange outlived [McpHttpRequest.timeoutMs], which is
     * [McpRequestTimeoutException]. Cancelling the caller cancels the exchange.
     *
     * [handle] runs in the caller's own coroutine, and a
     * [CancellationException][kotlinx.coroutines.CancellationException] it
     * throws is rethrown as it is, never turned into a failure. A caller that
     * emits to a flow from inside [handle] depends on both: the emission is in
     * the collector's context, and a collector that has seen enough stops the
     * exchange by the usual means.
     */
    suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T>

    /** Release the underlying client. A later [exchange] opens a new one. */
    suspend fun close(): Result<Unit>
}

/** The two methods the Streamable HTTP transport sends: a message, and the end of a session. */
enum class McpHttpMethod { POST, DELETE }

/**
 * A request to an MCP endpoint.
 *
 * @property headers Every header to send except `Content-Type`, which is
 *   `application/json` whenever there is a [body].
 * @property body A JSON-RPC message, or null for a request without one.
 * @property timeoutMs How long the whole exchange may take, from the first
 *   byte sent to the moment the response has been handled.
 */
class McpHttpRequest(
    val method: McpHttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val timeoutMs: Long,
) {
    /** Names the headers and never their values: one of them is the bearer token. */
    override fun toString(): String =
        "McpHttpRequest(method=$method, url=$url, headers=${headers.keys.sorted()}, " +
            "body=${if (body == null) "none" else "${body.length} chars"}, timeoutMs=$timeoutMs)"
}

/**
 * A response whose body has not been read yet.
 *
 * @param headers Response headers. Names are matched without regard to case;
 *   a header sent more than once is joined with `", "`.
 */
class McpHttpResponse(
    val status: Int,
    headers: Map<String, String>,
    val body: McpHttpBody,
) {
    private val headers: Map<String, String> = headers.mapKeys { (name, _) -> name.lowercase() }

    /** The value of the header called [name], whatever its case on the wire, or null. */
    fun header(name: String): String? = headers[name.lowercase()]

    /** The media type of the body, lower-cased and without parameters, or null when none was declared. */
    val mediaType: String?
        get() = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** A response body, read as it arrives. Read it one way or the other, not both. */
interface McpHttpBody {

    /**
     * The next line, without its terminator, or null when the body has ended.
     * Suspends until a whole line has arrived, so an event stream is read at
     * the pace the server writes it.
     */
    suspend fun readLine(): String?

    /** Everything that is left, as text. Returns once the body has ended. */
    suspend fun readText(): String
}

/**
 * [McpHttpTransport] over a Ktor [HttpClient].
 *
 * The five platform [HttpClientHandler]s are this class with their own engine,
 * so the wire behaviour (what is sent, how a body is streamed, what counts as a
 * timeout) is written once. Tests hand it a client over a mock engine.
 *
 * The client is built on first use, and the engine it is built with must not
 * time a response out by itself: [McpHttpRequest.timeoutMs] is the only clock,
 * and an engine's own read timeout would cut an event stream that is merely
 * quiet.
 *
 * ### Letting go of a body
 *
 * A handler may return before the body has ended: the answer it wanted was the
 * third event of a stream the server is in no hurry to close. Ktor's own
 * release of a response waits for the engine to finish reading, and an engine
 * blocked on a silent socket with no read timeout never does. So a body that is
 * still open when the handler returns is given [DRAIN_GRACE_MS] to end by
 * itself, which is the usual case and keeps the connection reusable, and is
 * then cancelled, which makes the engine drop the connection.
 */
class KtorMcpHttpTransport(
    private val clientFactory: () -> HttpClient,
) : McpHttpTransport {

    private val clientLock = Mutex()
    private var client: HttpClient? = null

    override suspend fun <T> exchange(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
    ): Result<T> {
        // Whether the response has been handed to `handle`: from then on a cancellation is the
        // handler's own business, or the caller's.
        var handing = false
        return try {
            send(request, handle, onResponse = { handing = true })
        } catch (e: CancellationException) {
            if (handing) throw e
            // Cancelled by the caller: stop. Cancelled from inside, because the engine gave up on
            // the call, is a failure like any other; rethrowing it would end the caller as if it
            // had been cancelled.
            currentCoroutineContext().ensureActive()
            Result.failure(McpConnectionException("The HTTP exchange was abandoned: ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun <T> send(
        request: McpHttpRequest,
        handle: suspend (McpHttpResponse) -> T,
        onResponse: () -> Unit,
    ): Result<T> {
        val http = clientLock.withLock { client ?: clientFactory().also { client = it } }

        val handled = withTimeoutOrNull(request.timeoutMs) {
            val statement = http.prepareRequest {
                url(request.url)
                method = when (request.method) {
                    McpHttpMethod.POST -> HttpMethod.Post
                    McpHttpMethod.DELETE -> HttpMethod.Delete
                }
                request.headers.forEach { (name, value) -> header(name, value) }
                if (request.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(request.body)
                }
            }
            // `execute` hands the response over before the body is read and releases it when the
            // block returns, which is what makes the body a stream rather than a buffer.
            val value = statement.execute { response ->
                val body = KtorBody(response)
                onResponse()
                try {
                    handle(McpHttpResponse(response.status.value, response.headerMap(), body))
                } finally {
                    letGoOf(response, body)
                }
            }
            Handled(value)
        }

        return if (handled == null) {
            Result.failure(McpRequestTimeoutException(request.timeoutMs))
        } else {
            Result.success(handled.value)
        }
    }

    override suspend fun close(): Result<Unit> = clientLock.withLock {
        runCatching {
            client?.close()
            client = null
        }
    }

    /** Distinguishes "the handler returned null" from "the exchange timed out". */
    private class Handled<T>(val value: T)

    /** See "Letting go of a body" on the class. */
    private suspend fun letGoOf(response: HttpResponse, body: KtorBody) {
        if (body.ended) return

        var ended = false
        try {
            ended = withTimeoutOrNull(DRAIN_GRACE_MS) {
                body.drain()
                true
            } ?: false
        } catch (e: McpConnectionException) {
            // The body broke while being drained. There is nothing left to wait for, and the
            // handler's own outcome is the one that counts.
        } finally {
            // Also reached when the caller was cancelled mid-drain: the body is dropped either way.
            if (!ended) response.cancel()
        }
    }

    private fun HttpResponse.headerMap(): Map<String, String> = buildMap {
        headers.names().forEach { name ->
            headers.getAll(name)?.let { values -> put(name, values.joinToString(", ")) }
        }
    }

    private class KtorBody(private val response: HttpResponse) : McpHttpBody {

        private var channel: ByteReadChannel? = null

        /** Whether the body has been read to its end. */
        var ended = false
            private set

        override suspend fun readLine(): String? = reading {
            val open = channel ?: response.bodyAsChannel().also { channel = it }
            open.readUTF8Line().also { line -> if (line == null) ended = true }
        }

        override suspend fun readText(): String = reading {
            response.bodyAsText().also { ended = true }
        }

        /**
         * A read that the engine cancels while the reader itself is not cancelled is a broken
         * body, and is reported as one. Left as a cancellation it would pass for the reader's own.
         */
        private suspend inline fun <R> reading(read: () -> R): R = try {
            read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw McpConnectionException("The response body was cut off: ${e.message}", e)
        }

        suspend fun drain() {
            while (readLine() != null) {
                // Whatever follows the part the handler wanted is not for anyone.
            }
        }
    }

    private companion object {

        /** How long a body the handler left unfinished gets to end by itself before it is dropped. */
        const val DRAIN_GRACE_MS = 250L
    }
}
