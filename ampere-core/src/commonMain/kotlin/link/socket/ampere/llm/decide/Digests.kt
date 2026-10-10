package link.socket.ampere.llm.decide

import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/**
 * The canonical JSON a [Question] is versioned and sent with. One
 * configuration, kept private, so a question's version never shifts with a
 * caller's formatting choices; the discriminator is `type`, which is what the
 * wire format calls it.
 */
internal val DecisionJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    prettyPrint = false
    ignoreUnknownKeys = true
}

/**
 * The SHA-256 hex digest of a decision call's state (AMPR-384, J4).
 *
 * State may hold a person's data, so the record of a call carries this and
 * never the state. Full width rather than the 16-character emission dedup
 * digest: this is a join key across every judgment ever recorded on a state,
 * not a short-window dedup key.
 */
fun stateDigest(state: String): String = state.encodeUtf8().sha256().hex()

/**
 * The version of a question: the SHA-256 hex digest of its canonical JSON.
 *
 * A reworded instruction, a changed criterion or a reordered option is a
 * different question, and the record says so without the caller having to
 * maintain a version number by hand.
 */
val Question.version: String
    get() = DecisionJson.encodeToString(Question.serializer(), this).encodeUtf8().sha256().hex()
