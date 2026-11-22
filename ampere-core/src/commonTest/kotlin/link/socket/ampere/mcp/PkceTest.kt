package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PKCE (RFC 7636) and the random values behind it, on every target `commonTest` runs on (AMPR-516). */
class PkceTest {

    @Test
    fun `S256 matches the RFC 7636 appendix B vector`() {
        val pkce = PkceChallenge.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        assertEquals("S256", PkceChallenge.METHOD)
    }

    @Test
    fun `a generated verifier is 64 unreserved characters and its challenge is unpadded`() {
        val pkce = PkceChallenge.generate(::secureRandomBytes)

        assertEquals(64, pkce.verifier.length)
        assertTrue(pkce.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "not base64url")
        assertEquals(43, pkce.challenge.length)
        assertFalse('=' in pkce.challenge)
        assertEquals(PkceChallenge.of(pkce.verifier).challenge, pkce.challenge)
    }

    @Test
    fun `two generated verifiers differ`() {
        assertNotEquals(
            PkceChallenge.generate(::secureRandomBytes).verifier,
            PkceChallenge.generate(::secureRandomBytes).verifier,
        )
    }

    @Test
    fun `secure random bytes come in the length asked for and do not repeat`() {
        listOf(0, 1, 16, 24, 48, 50).forEach { count ->
            assertEquals(count, secureRandomBytes(count).size)
        }
        assertFalse(secureRandomBytes(24).contentEquals(secureRandomBytes(24)))
        assertTrue(secureRandomBytes(48).any { it != 0.toByte() })
    }

    @Test
    fun `toString shows the challenge and never the verifier`() {
        val pkce = PkceChallenge.of("fake-verifier-fake-verifier-fake-verifier-fake")

        val rendered = pkce.toString()

        assertFalse("fake-verifier" in rendered, rendered)
        assertTrue(pkce.challenge in rendered, rendered)
    }

    @Test
    fun `base64url is the URL-safe alphabet without padding`() {
        assertEquals("-_-_", base64Url(byteArrayOf(-5, -1, -65)))
        assertEquals("AA", base64Url(byteArrayOf(0)))
    }
}
