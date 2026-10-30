package link.socket.ampere.mcp

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [MacOsKeychain] against a recorded `security`: what it is asked to run, and how its
 * exit status is read. The real tool is exercised by [MacOsKeychainProbe].
 */
class MacOsKeychainTest {

    private class Run(val arguments: List<String>, val stdin: String?)

    private class FakeSecurity(
        private val answer: (Run) -> SecurityResult = { SecurityResult(0, "", "") },
    ) : SecurityCommand {
        val runs = mutableListOf<Run>()

        override suspend fun run(arguments: List<String>, stdin: String?): SecurityResult {
            val run = Run(arguments, stdin)
            runs += run
            return answer(run)
        }
    }

    private val secret = """{"authToken":"fake-access-0","refreshToken":"fake-refresh-0"}"""
    private val service = "ampere.link:mcp:work-source"
    private val account = "https://mcp.invalid/mcp"

    @Test
    fun `a write sends the secret on stdin and never on the command line`() = runBlocking<Unit> {
        val security = FakeSecurity()

        MacOsKeychain(security, operatingSystem = "Mac OS X").write(service, account, secret).getOrThrow()

        val run = security.runs.single()
        assertEquals(listOf("-i"), run.arguments)
        assertTrue(run.arguments.none { "fake-" in it })
        val stdin = run.stdin.orEmpty()
        assertTrue(stdin.startsWith("add-generic-password -U -s \"$service\" -a \"$account\" -X "), stdin)
        assertFalse("fake-access-0" in stdin, "the secret is hex-encoded, not spelled out")
        assertEquals(secret, decodeStdinPayload(stdin))
    }

    @Test
    fun `a read decodes what a write stored`() = runBlocking<Unit> {
        val stored = Base64.getEncoder().encodeToString(secret.toByteArray())
        val security = FakeSecurity { SecurityResult(0, "$stored\n", "") }

        val read = MacOsKeychain(security, operatingSystem = "Mac OS X").read(service, account).getOrThrow()

        assertEquals(secret, read)
        assertEquals(
            listOf("find-generic-password", "-s", service, "-a", account, "-w"),
            security.runs.single().arguments,
        )
        assertNull(security.runs.single().stdin)
    }

    @Test
    fun `a non-ASCII secret round-trips through the encoding`() = runBlocking<Unit> {
        val unusual = """{"authToken":"fake-é-ü-✓"}"""
        val written = FakeSecurity()
        val keychain = MacOsKeychain(written, operatingSystem = "Mac OS X")
        keychain.write(service, account, unusual).getOrThrow()

        val hex = written.runs.single().stdin.orEmpty().substringAfter("-X ").trim()
        val printed = String(hexToBytes(hex), Charsets.UTF_8)
        val read = MacOsKeychain(FakeSecurity { SecurityResult(0, printed + "\n", "") }, operatingSystem = "Mac OS X")
            .read(service, account)
            .getOrThrow()

        assertEquals(unusual, read)
    }

    @Test
    fun `an item that is not there reads as null and deletes as done`() = runBlocking<Unit> {
        val notFound = SecurityResult(44, "", "security: SecKeychainSearchCopyNext: The specified item was not found")
        val keychain = MacOsKeychain(FakeSecurity { notFound }, operatingSystem = "Mac OS X")

        assertNull(keychain.read(service, account).getOrThrow())
        assertTrue(keychain.delete(service, account).isSuccess)
    }

    @Test
    fun `any other exit status is a failure that does not quote the secret`() = runBlocking<Unit> {
        val security = FakeSecurity { run ->
            // A tool that echoed its input would still not get it into the failure.
            SecurityResult(51, "", "security: write refused: ${run.stdin?.substringAfter("-X ")?.trim()}")
        }

        val failure = MacOsKeychain(security, operatingSystem = "Mac OS X")
            .write(service, account, secret)
            .exceptionOrNull()

        val message = failure?.message.orEmpty()
        assertTrue("exited 51" in message, message)
        assertTrue("<redacted>" in message, message)
        val hex = security.runs.single().stdin.orEmpty().substringAfter("-X ").trim()
        assertFalse(hex in message, message)
    }

    @Test
    fun `an item another tool wrote is refused without being quoted`() = runBlocking<Unit> {
        val security = FakeSecurity { SecurityResult(0, "fake-plain-token with spaces!\n", "") }

        val failure = MacOsKeychain(security, operatingSystem = "Mac OS X").read(service, account).exceptionOrNull()

        val message = failure?.message.orEmpty()
        assertTrue("not written by Ampere" in message, message)
        assertFalse("fake-plain-token" in message, message)
    }

    @Test
    fun `a service or account the interactive command cannot quote is refused before running anything`() =
        runBlocking<Unit> {
            val security = FakeSecurity()
            val keychain = MacOsKeychain(security, operatingSystem = "Mac OS X")

            assertTrue(keychain.write("ampere.link:\"odd\"", account, secret).isFailure)
            assertTrue(keychain.write(service, "line\nbreak", secret).isFailure)
            assertTrue(security.runs.isEmpty())
        }

    @Test
    fun `on any OS but macOS every call fails and says why`() = runBlocking<Unit> {
        val security = FakeSecurity()
        val keychain = MacOsKeychain(security, operatingSystem = "Linux")

        val failure = keychain.read(service, account).exceptionOrNull()

        assertIs<UnsupportedOperationException>(failure)
        assertTrue("macOS-only" in failure.message.orEmpty())
        assertTrue(keychain.write(service, account, secret).isFailure)
        assertTrue(security.runs.isEmpty())
    }

    @Test
    fun `a SecurityResult never prints what it read`() {
        val rendered = SecurityResult(0, "fake-stdout-secret", "").toString()

        assertFalse("fake-stdout-secret" in rendered, rendered)
    }

    private fun decodeStdinPayload(stdin: String): String {
        val hex = stdin.substringAfter("-X ").trim()
        return String(Base64.getDecoder().decode(hexToBytes(hex)), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
