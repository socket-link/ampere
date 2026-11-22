package link.socket.ampere.agents.tools.mcp.connection

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A [ScriptedMcpServer] behind a real HTTP server on the loopback interface
 * (AMPR-514).
 *
 * The scripted server decides what to answer; this class puts those answers on
 * a socket, so a client under test goes through its real HTTP engine. What the
 * scripted server records is therefore what arrived on the wire: every header
 * the engine added, kept or dropped.
 *
 * An event stream is written one event at a time and flushed after each, with
 * chunked transfer encoding and no content length, which is how a server that
 * streams behaves and what a client that buffers a whole body cannot read
 * until the stream closes.
 */
internal class StreamableHttpFixture(
    val server: ScriptedMcpServer = ScriptedMcpServer(),
) : AutoCloseable {

    private val executor = Executors.newCachedThreadPool()
    private val http: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** Opened when the fixture closes. Until then a held stream and a stalled request stay as they are. */
    private val released = CountDownLatch(1)

    /** Counted down when a stream has written its last event and is being held open. */
    val holdingStreamOpen = CountDownLatch(1)

    val endpoint: String
        get() = "http://127.0.0.1:${http.address.port}$PATH"

    init {
        http.createContext(PATH) { exchange ->
            try {
                answer(exchange)
            } finally {
                exchange.close()
            }
        }
        http.executor = executor
        http.start()
    }

    /** Whether the fixture is still running: a held stream has not been let go. */
    val isHolding: Boolean
        get() = released.count > 0

    private fun answer(exchange: HttpExchange) {
        val headers = exchange.requestHeaders.mapValues { (_, values) -> values.joinToString(", ") }
        val body = exchange.requestBody.readBytes().decodeToString().takeIf { it.isNotEmpty() }
        val reply = synchronized(server) { server.handle(exchange.requestMethod, headers, body) }

        if (reply.stall) {
            released.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)
            return
        }

        reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }

        val events = reply.events
        val whole = reply.body
        when {
            events != null -> {
                // A length of zero means "unknown": the body is sent chunked, as it is written.
                exchange.sendResponseHeaders(reply.status, 0)
                events.forEach { event ->
                    exchange.responseBody.write(event.encodeToByteArray())
                    exchange.responseBody.flush()
                }
                if (reply.holdOpen) {
                    holdingStreamOpen.countDown()
                    released.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)
                }
            }

            whole != null -> {
                val bytes = whole.encodeToByteArray()
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }

            else -> exchange.sendResponseHeaders(reply.status, NO_BODY)
        }
    }

    override fun close() {
        released.countDown()
        http.stop(0)
        executor.shutdownNow()
    }

    private companion object {
        const val PATH = "/mcp"
        const val NO_BODY = -1L

        /** A test that forgets to close the fixture does not pin a thread for ever. */
        const val HOLD_LIMIT_SECONDS = 60L
    }
}
