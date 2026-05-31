package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.Serializable

/**
 * Shared confidence enum used across the cognition layer.
 *
 * Currently consumed by:
 *  - [PerceptionEvaluator] when parsing per-insight confidence from LLM JSON
 *  - [OutcomeEvaluator] when parsing per-learning confidence from LLM JSON
 *  - [link.socket.ampere.agents.domain.emission.Emission] for CHI Emissions
 *
 * Prompt strings remain lower-case (`"high" | "medium" | "low"`) for LLM
 * compatibility; [parseOrNull] / [parseOrDefault] handle the conversion to
 * this enum on the Kotlin side.
 */
@Serializable
enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {

        /**
         * Parse a prompt-style confidence string (case-insensitive) and return
         * `null` if the input is not recognised.
         */
        fun parseOrNull(value: String): Confidence? = when (value.trim().lowercase()) {
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            else -> null
        }

        /**
         * Parse a prompt-style confidence string, falling back to [default]
         * when the input is `null`, blank, or unrecognised. Defaults to
         * [MEDIUM] to match historical evaluator behaviour.
         */
        fun parseOrDefault(value: String?, default: Confidence = MEDIUM): Confidence =
            value?.let { parseOrNull(it) } ?: default
    }
}
