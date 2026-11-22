package link.socket.ampere.mcp

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The JVM's default [OAuthBrowserStep]: a loopback redirect (RFC 8252 §7.3),
 * for a desktop app or a CLI (AMPR-516).
 *
 * [begin] binds a listener and names `http://127.0.0.1:<port><path>` as the
 * redirect URI; [OAuthBrowserSession.authorize] hands the authorization URL to
 * [openUrl] and waits for the browser to arrive on that URI.
 *
 * - **It binds `127.0.0.1` and nothing else.** Never a wildcard address, so no
 *   other machine can reach the listener, and never `localhost`, which a hosts
 *   file can point elsewhere.
 * - **The port is ephemeral**, chosen by the OS at bind time, so two
 *   authorizations never collide and nothing squats on a known port.
 * - **One request on one path, then it closes.** The first `GET` on [path] is
 *   the redirect: it is answered with a page that says to return to the app,
 *   and the listener is closed before that answer is acted on. Anything else
 *   that connects first (another path, another method, a connection a browser
 *   opened and never used) gets a 404 and is not the redirect; after
 *   [MAX_STRAY_CONNECTIONS] of those the listener gives up.
 * - **Nothing from the request is echoed or logged.** The page is fixed text,
 *   and a failure names what happened, never the request line: that line is
 *   where the authorization code is.
 *
 * Whether the redirect answers *this* authorization is not decided here.
 * [McpOAuthAuthorizer] checks `state` and `iss`; a forged request to the
 * listener is refused there, and has spent the one request this listener
 * accepts, so the person authorizes again.
 *
 * @param openUrl Puts the authorization URL in front of the person. The
 *   default opens the system browser; a CLI also prints it, for a machine
 *   whose browser is somewhere else.
 * @param timeout How long to wait for the redirect before giving up.
 * @param path The path of the redirect URI.
 */
class LoopbackOAuthBrowserStep(
    private val openUrl: suspend (String) -> Unit = { SystemBrowser.open(it) },
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val path: String = DEFAULT_PATH,
) : OAuthBrowserStep {

    init {
        require(path.startsWith("/") && path.none { it == '?' || it == '#' || it.isWhitespace() }) {
            "path must be an absolute URL path with no query, was $path"
        }
    }

    override suspend fun begin(): Result<OAuthBrowserSession> = withContext(Dispatchers.IO) {
        runCatching<OAuthBrowserSession> {
            // The address as bytes: no name is resolved, so nothing can answer for it but this machine.
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            Session(ServerSocket(0, BACKLOG, loopback))
        }
    }

    private inner class Session(private val server: ServerSocket) : OAuthBrowserSession {

        override val redirectUri: String = "http://127.0.0.1:${server.localPort}$path"

        override suspend fun authorize(authorizationUrl: String): Result<OAuthRedirect> =
            try {
                openUrl(authorizationUrl)
                withContext(Dispatchers.IO) { awaitRedirect() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the kind of failure: an I/O error on this socket has no business quoting what it read.
                Result.failure(LoopbackRedirectException("the loopback listener failed (${e::class.simpleName})"))
            } finally {
                close()
            }

        override suspend fun close() {
            runCatching { server.close() }
        }

        private suspend fun awaitRedirect(): Result<OAuthRedirect> {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            var strays = 0
            // A short accept timeout, so cancellation and the deadline are both noticed promptly.
            server.soTimeout = POLL.inWholeMilliseconds.toInt()

            while (strays < MAX_STRAY_CONNECTIONS) {
                currentCoroutineContext().ensureActive()
                if (deadline.hasPassedNow()) {
                    return Result.failure(LoopbackRedirectException("no redirect arrived within $timeout"))
                }

                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                socket.use { connection ->
                    connection.soTimeout = REQUEST_TIMEOUT.inWholeMilliseconds.toInt()
                    val target = runCatching { requestTarget(connection.getInputStream()) }.getOrNull()
                    val query = target?.takeIf { it.substringBefore('?') == path }?.substringAfter('?', "")

                    if (query == null) {
                        runCatching { respond(connection, "404 Not Found", NOT_FOUND_PAGE) }
                        strays += 1
                    } else {
                        runCatching { respond(connection, "200 OK", RECEIVED_PAGE) }
                        // Closed before the redirect is acted on: this listener has taken its one request.
                        close()
                        val arrived = if (query.isEmpty()) redirectUri else "$redirectUri?$query"
                        return Result.success(OAuthRedirect(arrived))
                    }
                }
            }
            return Result.failure(
                LoopbackRedirectException("$MAX_STRAY_CONNECTIONS connections arrived that were not the redirect"),
            )
        }
    }

    companion object {

        val DEFAULT_TIMEOUT: Duration = 5.minutes

        const val DEFAULT_PATH: String = "/oauth/callback"

        /** How many connections that are not the redirect are turned away before the listener gives up. */
        const val MAX_STRAY_CONNECTIONS: Int = 8

        private const val BACKLOG = 8
        private val POLL = 0.25.seconds
        private val REQUEST_TIMEOUT = 2.seconds
        private const val MAX_REQUEST_BYTES = 16 * 1024

        private const val RECEIVED_PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>Authorization received</title></head>" +
                "<body><p>Authorization received. You can close this window and return to the app.</p></body></html>"

        private const val NOT_FOUND_PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>Not found</title></head>" +
                "<body><p>Not found.</p></body></html>"

        /**
         * The request target of a `GET`, having read the request through the
         * end of its headers so the answer is not cut off by unread input. Null
         * for any other method, a malformed request, or one past
         * [MAX_REQUEST_BYTES].
         */
        private fun requestTarget(input: InputStream): String? {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n") && !head.endsWith("\n\n")) {
                val next = input.read()
                if (next < 0 || head.length >= MAX_REQUEST_BYTES) return null
                head.append(next.toChar())
            }
            val requestLine = head.lineSequence().firstOrNull()?.trim()?.split(' ') ?: return null
            if (requestLine.size != 3 || requestLine[0] != "GET" || !requestLine[2].startsWith("HTTP/")) return null
            return requestLine[1].takeIf { it.startsWith("/") }
        }

        private fun respond(connection: Socket, status: String, page: String) {
            val body = page.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            connection.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        }
    }
}

/** Why a [LoopbackOAuthBrowserStep] brought no redirect back. The message never quotes a request. */
class LoopbackRedirectException(message: String) : Exception(message)

/**
 * Opens a URL in the person's default browser by asking the operating system
 * to (`open`, `xdg-open`, or `rundll32` on Windows), with no AWT.
 */
object SystemBrowser {

    /**
     * @return Whether the OS accepted the request. False for anything that is
     *   not an `http(s)` URL — this is handed to a command, and only a web
     *   address belongs there — and when there is no such command or it failed.
     */
    fun open(url: String, operatingSystem: String = System.getProperty("os.name").orEmpty()): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        val os = operatingSystem.lowercase()
        val command = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.close()
            !process.waitFor(OPEN_WAIT_SECONDS, TimeUnit.SECONDS) || process.exitValue() == 0
        }.getOrDefault(false)
    }

    private const val OPEN_WAIT_SECONDS = 5L
}
