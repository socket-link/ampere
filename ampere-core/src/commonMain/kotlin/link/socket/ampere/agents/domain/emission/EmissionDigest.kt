package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/**
 * Canonical [Json] used for [inputDigest]. Kept private so the hash
 * function is the only public way to produce a dedup digest — any change
 * to these flags shifts every previously-stored digest, so callers must
 * not pick their own configuration.
 *
 * Mirrors `RepositoryFactory.DEFAULT_JSON`'s `classDiscriminator = "type"`
 * convention so the digest matches what the bus would serialize.
 */
private val DigestJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Number of hex characters retained from the SHA-256 digest. 16 chars =
 * 64 bits of collision resistance, which is sufficient for the dedup
 * window (seconds to minutes on a single agent) and keeps the digest
 * short enough to log inline.
 */
private const val DIGEST_HEX_CHARS = 16

/**
 * Content-deterministic digest of an [EmissionPayload]. The same payload
 * always yields the same digest; differing payloads always yield
 * differing digests (modulo SHA-256 collisions).
 *
 * Stability comes from two pieces:
 *  - `kotlinx.serialization` writes data-class fields in declaration order
 *  - [DigestJson] forces a single canonical configuration
 *
 * Field ordering in source code therefore does not affect the digest, and
 * adding a default-valued field to a payload variant changes the digest
 * — both of which are the intended semantics.
 */
fun inputDigest(payload: EmissionPayload): String {
    val json = DigestJson.encodeToString(EmissionPayload.serializer(), payload)
    return json.encodeUtf8().sha256().hex().take(DIGEST_HEX_CHARS)
}
