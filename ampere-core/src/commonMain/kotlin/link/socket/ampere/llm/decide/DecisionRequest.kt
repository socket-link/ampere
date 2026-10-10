package link.socket.ampere.llm.decide

import kotlinx.serialization.Serializable
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.api.model.TokenUsage

/**
 * What a decision call sends (AMPR-384, J2): a state plus questions keyed by
 * id. This is the System One wire shape, vendor-free; its serialized form is
 * the request body a hosted provider reads, minus whatever that provider's
 * adapter adds (a `model` field, say).
 *
 * The state is text. A caller with structured state renders it (JSON is fine)
 * before it gets here. The state never appears in any event; the record of the
 * call carries [stateDigest] instead.
 */
@AmpereStableApi
@Serializable
data class DecisionRequest(
    val state: String,
    val questions: Map<String, Question>,
) {
    init {
        require(questions.isNotEmpty()) { "A decision request needs at least one question" }
        require(questions.keys.none { it.isBlank() }) { "A question id cannot be blank" }
    }
}

/**
 * What a decision call returns: one [Judgment] per question id, and the
 * usage the provider reported for the call. A transport that reports no
 * usage leaves the default, and the record books the call with none.
 */
@AmpereStableApi
@Serializable
data class DecisionResponse(
    val judgments: Map<String, Judgment>,
    val usage: TokenUsage = TokenUsage(),
)
