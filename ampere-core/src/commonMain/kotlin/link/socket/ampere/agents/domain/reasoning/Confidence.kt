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
 *
 * Every level is something a generating model said about itself, so its
 * [source] is always [ConfidenceSource.SELF_REPORTED] (AMPR-384, J5). A
 * measured confidence is a probability on a
 * [link.socket.ampere.llm.decide.Judgment], not a level here; the two are kept
 * apart on purpose, and [asProbability] is the one sanctioned bridge from a
 * level to a number.
 */
@Serializable
enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
    ;

    /**
     * Where this value came from. A level is parsed from generated JSON, so it
     * is always [ConfidenceSource.SELF_REPORTED]; nothing measured is ever a
     * [Confidence].
     */
    val source: ConfidenceSource
        get() = ConfidenceSource.SELF_REPORTED

    /**
     * The F10 mapping of a self-reported level to a number:
     * `LOW → 0.25`, `MEDIUM → 0.5`, `HIGH → 0.75`.
     *
     * This applies to self-reported values only, which is every value of this
     * enum. The result is a self-reported number still — no band is ever fitted
     * on it (see [link.socket.ampere.llm.decide.BandFitGuard]).
     */
    fun asProbability(): Double = when (this) {
        LOW -> 0.25
        MEDIUM -> 0.5
        HIGH -> 0.75
    }

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
