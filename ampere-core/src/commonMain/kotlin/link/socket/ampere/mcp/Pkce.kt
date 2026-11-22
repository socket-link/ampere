package link.socket.ampere.mcp

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * A PKCE verifier and its `S256` challenge (RFC 7636 §4).
 *
 * The [challenge] goes out in the authorization request, where anyone watching
 * the browser sees it; the [verifier] goes only to the token endpoint, with the
 * code, and proves the two requests came from the same client. So the verifier
 * is a secret for as long as the flow runs, and [toString] never shows it.
 */
internal class PkceChallenge private constructor(
    val verifier: String,
    val challenge: String,
) {

    override fun toString(): String =
        "PkceChallenge(verifier=${redacted(verifier)}, challenge=$challenge, method=$METHOD)"

    companion object {

        /** The only method this client offers. `plain` protects nothing, and an MCP server must support `S256`. */
        const val METHOD: String = "S256"

        /** A fresh verifier: 48 random octets as unpadded base64url, 64 of RFC 7636's 43 to 128 characters. */
        fun generate(randomBytes: (Int) -> ByteArray): PkceChallenge = of(base64Url(randomBytes(VERIFIER_BYTES)))

        /** The challenge for [verifier]: `BASE64URL(SHA256(ASCII(verifier)))`, unpadded. */
        fun of(verifier: String): PkceChallenge =
            PkceChallenge(verifier, verifier.encodeUtf8().sha256().base64Url().trimEnd('='))

        private const val VERIFIER_BYTES = 48
    }
}

/** [bytes] as unpadded base64url (RFC 4648 §5), the alphabet RFC 7636 and a `state` value both want. */
internal fun base64Url(bytes: ByteArray): String = bytes.toByteString().base64Url().trimEnd('=')

/**
 * [count] octets from the platform's cryptographically secure generator.
 *
 * Drawn from [Uuid.random], which is the one such generator the Kotlin standard
 * library exposes on every target this module builds (JVM, Android, iOS, JS and
 * wasmJs); `kotlin.random.Random` is not one. A version-4 UUID carries 122
 * random bits in 16 octets, so the 48 octets behind a PKCE verifier hold 366
 * bits and the 24 behind a `state` hold 183, both past the 128 that RFC 6749
 * §10.10 asks of a value an attacker must not guess.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val chunk = Uuid.random().toByteArray()
        val take = minOf(chunk.size, count - filled)
        chunk.copyInto(bytes, destinationOffset = filled, startIndex = 0, endIndex = take)
        filled += take
    }
    return bytes
}
