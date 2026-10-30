package link.socket.ampere.mcp

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.link.LinkId

/**
 * The persistent binding against the real macOS keychain: bind, read back
 * through a second binding (a second process, in effect), replace, unbind.
 *
 * Opt-in, because it writes to the login keychain of whoever runs it. It uses a
 * throwaway Link id and removes its item however the test ends. Not in CI: the
 * JVM jobs run on Linux, where the store refuses every call by design.
 *
 * ```bash
 * AMPERE_KEYCHAIN_PROBE=1 ./gradlew :ampere-core:jvmTest --tests '*MacOsKeychainProbe*'
 * ```
 */
class MacOsKeychainProbe {

    @Test
    fun `bind resolve replace and unbind against the login keychain`() {
        if (System.getenv(PROBE) != "1") return println("skipped: $PROBE is not 1")
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return println("skipped: not macOS")

        val linkId = LinkId("probe-${UUID.randomUUID()}")
        val mcpUri = "https://mcp.invalid/probe"
        val credential = McpCredential(
            authToken = "fake-probe-access-é",
            refreshToken = "fake-probe-refresh",
            expiresAt = Instant.parse("2026-10-30T12:00:00Z"),
            oauthClient = McpOAuthClient("https://issuer.invalid/token", "fake-probe-client", "fake \"quoted\" secret"),
        )

        runBlocking<Unit> {
            val writer = KeychainMcpCredentialBinding(MacOsKeychain())
            try {
                writer.bind(linkId, mcpUri, credential).getOrThrow()
                val reader = KeychainMcpCredentialBinding(MacOsKeychain())
                assertEquals(credential, reader.resolve(linkId, mcpUri).getOrThrow())

                val rotated = credential.copy(authToken = "fake-probe-access-2", refreshToken = "fake-probe-refresh-2")
                writer.bind(linkId, mcpUri, rotated).getOrThrow()
                assertEquals(rotated, reader.resolve(linkId, mcpUri).getOrThrow())

                writer.unbind(linkId, mcpUri).getOrThrow()
                assertNull(reader.resolve(linkId, mcpUri).getOrThrow())
            } finally {
                writer.unbind(linkId, mcpUri)
            }
        }
    }

    private companion object {
        const val PROBE = "AMPERE_KEYCHAIN_PROBE"
    }
}
