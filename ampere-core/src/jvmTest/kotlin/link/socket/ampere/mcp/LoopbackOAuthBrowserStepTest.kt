package link.socket.ampere.mcp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * The JVM loopback listener (AMPR-516): 127.0.0.1 only, an ephemeral port, one
 * request on one path, then closed.
 */
class LoopbackOAuthBrowserStepTest {

    private val redirectPattern = Regex("""http://127\.0\.0\.1:(\d+)/oauth/callback""")
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun portOf(session: OAuthBrowserSession): Int =
        redirectPattern.matchEntire(session.redirectUri)!!.groupValues[1].toInt()

    /** Connects and writes one request without waiting for the answer, as a browser that was sent here does. */
    private fun send(port: Int, requestLine: String): Socket =
        Socket(loopback, port).also { socket ->
            val request = "$requestLine\r\nHost: 127.0.0.1:$port\r\nAccept: text/html\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }

    private fun answerOf(socket: Socket): String =
        socket.use { it.getInputStream().readBytes().toString(Charsets.UTF_8) }

    private fun isListening(port: Int, address: InetAddress = loopback): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(address, port), 500) } }.isSuccess

    @Test
    fun `it listens on 127_0_0_1 on a port the OS chose and on no other address`() = runBlocking<Unit> {
        val step = LoopbackOAuthBrowserStep(openUrl = {})
        val first = step.begin().getOrThrow()
        val second = step.begin().getOrThrow()
        try {
            assertTrue(redirectPattern.matches(first.redirectUri), first.redirectUri)
            assertTrue(portOf(first) > 0)
            assertNotEquals(portOf(first), portOf(second), "each authorization gets its own port")
            assertTrue(isListening(portOf(first)))

            // Not a wildcard bind: the port answers on no other address this machine has.
            val elsewhere = NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterNot { it.isLoopbackAddress }
                .toList()
            elsewhere.forEach { address ->
                assertFalse(isListening(portOf(first), address), "the listener answered on $address")
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `the first GET on the path is the redirect and the listener is closed after it`() = runBlocking<Unit> {
        var browser: Socket? = null
        lateinit var session: OAuthBrowserSession
        val arriving = "GET /oauth/callback?code=fake-code-1&state=fake-state-1 HTTP/1.1"
        val step = LoopbackOAuthBrowserStep(openUrl = { browser = send(portOf(session), arriving) })
        session = step.begin().getOrThrow()

        val redirect = session.authorize("https://auth.invalid/authorize?state=fake-state-1").getOrThrow()

        assertEquals("${session.redirectUri}?code=fake-code-1&state=fake-state-1", redirect.uri)
        assertFalse("fake-code-1" in redirect.toString(), "the redirect renders without its query")
        val answer = answerOf(browser!!)
        assertTrue(answer.startsWith("HTTP/1.1 200 OK"), answer)
        assertFalse("fake-code-1" in answer || "fake-state-1" in answer, "the page echoes nothing from the request")
        assertFalse(isListening(portOf(session)), "one request, then closed")
    }

    @Test
    fun `other paths and other methods get a 404 and are not taken for the redirect`() = runBlocking<Unit> {
        val strays = mutableListOf<Socket>()
        lateinit var session: OAuthBrowserSession
        val step = LoopbackOAuthBrowserStep(
            openUrl = {
                val port = portOf(session)
                strays += send(port, "GET /favicon.ico HTTP/1.1")
                strays += send(port, "POST /oauth/callback?code=fake-forged-code HTTP/1.1")
                strays += send(port, "GET /oauth/callback/elsewhere?code=fake-forged-code HTTP/1.1")
                strays += send(port, "GET /oauth/callback?code=fake-code-1 HTTP/1.1")
            },
        )
        session = step.begin().getOrThrow()

        val redirect = session.authorize("https://auth.invalid/authorize").getOrThrow()

        assertEquals("${session.redirectUri}?code=fake-code-1", redirect.uri)
        val answers = strays.map(::answerOf)
        assertTrue(answers.take(3).all { it.startsWith("HTTP/1.1 404") }, answers.toString())
        assertTrue(answers.last().startsWith("HTTP/1.1 200"), answers.last())
    }

    @Test
    fun `no redirect within the timeout is a failure and the port is released`() = runBlocking<Unit> {
        val session = LoopbackOAuthBrowserStep(openUrl = {}, timeout = 300.milliseconds).begin().getOrThrow()
        val port = portOf(session)

        val failure = session.authorize("https://auth.invalid/authorize").exceptionOrNull()

        assertIs<LoopbackRedirectException>(failure)
        assertFalse(isListening(port))
    }

    @Test
    fun `a browser that cannot be opened is the failure and the port is released`() = runBlocking<Unit> {
        val session = LoopbackOAuthBrowserStep(
            openUrl = { error("no browser here: ${it.substringAfter('?')}") },
            timeout = 5.seconds,
        ).begin().getOrThrow()

        val failure = session.authorize("https://auth.invalid/authorize?state=fake-state-1").exceptionOrNull()

        assertIs<LoopbackRedirectException>(failure)
        assertFalse("fake-state-1" in failure.message.orEmpty(), "a failure quotes nothing it was handed")
        assertFalse(isListening(portOf(session)))
    }

    @Test
    fun `close releases the port and may be called twice`() = runBlocking<Unit> {
        val session = LoopbackOAuthBrowserStep(openUrl = {}).begin().getOrThrow()
        assertTrue(isListening(portOf(session)))

        session.close()
        session.close()

        assertFalse(isListening(portOf(session)))
    }

    @Test
    fun `the redirect path is configurable and must be a bare path`() = runBlocking<Unit> {
        val session = LoopbackOAuthBrowserStep(openUrl = {}, path = "/cb").begin().getOrThrow()
        try {
            assertTrue(session.redirectUri.endsWith("/cb"), session.redirectUri)
        } finally {
            session.close()
        }

        listOf("cb", "/cb?x=1", "/cb#x", "/c b").forEach { path ->
            assertTrue(runCatching { LoopbackOAuthBrowserStep(openUrl = {}, path = path) }.isFailure, path)
        }
    }

    @Test
    fun `the system browser is only ever handed a web address`() {
        // Nothing here may reach a real browser, so only the refusals are exercised.
        assertFalse(SystemBrowser.open("file:///etc/hosts"))
        assertFalse(SystemBrowser.open("-a Calculator"))
        assertFalse(SystemBrowser.open(""))
    }
}
