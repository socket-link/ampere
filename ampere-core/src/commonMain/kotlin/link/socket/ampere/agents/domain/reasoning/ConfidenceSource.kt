package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.Serializable

/**
 * Where a confidence came from (AMPR-384, J5).
 *
 * The two are different quantities and are never mixed in one column:
 *
 * - [MEASURED] — a probability a decision model returned over declared
 *   answers, or a one-hot the deterministic adapter constructed. The only kind
 *   a band is ever fitted on.
 * - [SELF_REPORTED] — a `low | medium | high` a generative model wrote about
 *   its own answer, parsed from JSON. Every [Confidence] level is one of these,
 *   and the F10 mapping (`0.25 / 0.5 / 0.75`) applies to them alone.
 */
@Serializable
enum class ConfidenceSource {
    MEASURED,
    SELF_REPORTED,
}
