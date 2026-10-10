package link.socket.ampere.llm.decide

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.api.AmpereStableApi

/**
 * A typed question a decision model answers about a state (AMPR-384, J3).
 *
 * A decision model generates no text. Every answer it can give is declared up
 * front, here, and it returns a probability per declared answer. The three
 * shapes are the System One wire format's three question types, and the
 * serialized form of each *is* the wire body — `{type, instructions, criteria}`
 * with the type as the class discriminator — so nothing translates between
 * this type and what a hosted provider reads.
 *
 * `criteria` is required on every question (the J2 amendment). It is the only
 * way a [Choice] declares its options, so it is part of the format rather than
 * a vendor detail; on a [Noul] it describes the two answers and on a [Score]
 * it describes the levels. [answerKeys] is the one vocabulary a
 * [Judgment.answer] and a [Judgment.distribution] are drawn from.
 *
 * `Reading`, `Meter`, `Calibration`, `Verdict` and `Decision` are taken names
 * in this codebase and mean other things; a question's answer is a [Judgment].
 */
@AmpereStableApi
@Serializable
sealed interface Question {

    /** What is being asked about the state. */
    val instructions: String

    /**
     * The keys an answer is drawn from, in declared order: `true`/`false` for a
     * [Noul], the option keys of a [Choice], the level indices of a [Score].
     */
    val answerKeys: List<String>

    /** The one-sentence description of [key], or null when the key is not declared. */
    fun describe(key: String): String?

    /**
     * A yes/no proposition. A decision model returns the probability of `true`.
     *
     * [criteria] holds exactly two entries, keyed [TRUE_KEY] and [FALSE_KEY], each
     * describing what that answer means for this question.
     */
    @Serializable
    @SerialName("noul")
    data class Noul(
        override val instructions: String,
        val criteria: Map<String, String>,
    ) : Question {

        init {
            require(instructions.isNotBlank()) { "A noul needs instructions" }
            require(criteria.keys == setOf(TRUE_KEY, FALSE_KEY)) {
                "A noul's criteria must be keyed exactly '$TRUE_KEY' and '$FALSE_KEY', was ${criteria.keys}"
            }
        }

        override val answerKeys: List<String>
            get() = listOf(TRUE_KEY, FALSE_KEY)

        override fun describe(key: String): String? = criteria[key]

        companion object {
            // Suffixed on purpose: a public member named TRUE or FALSE exports to the
            // Objective-C header as `NSString *TRUE`, which CoreFoundation's macro expands
            // to `*1` and the iOS build no longer parses.
            const val TRUE_KEY: String = "true"
            const val FALSE_KEY: String = "false"

            /** A noul from its two answer descriptions. */
            fun of(instructions: String, whenTrue: String, whenFalse: String): Noul =
                Noul(instructions = instructions, criteria = mapOf(TRUE_KEY to whenTrue, FALSE_KEY to whenFalse))
        }
    }

    /**
     * One of a declared set. A decision model returns the pick and a probability
     * per option.
     *
     * The options are the keys of [criteria], in declared order; each value is
     * the one-sentence description a model decides against. There is no
     * separate options list: a key with no description is not an option.
     */
    @Serializable
    @SerialName("choice")
    data class Choice(
        override val instructions: String,
        val criteria: Map<String, String>,
    ) : Question {

        init {
            require(instructions.isNotBlank()) { "A choice needs instructions" }
            require(criteria.size >= 2) { "A choice needs at least two options, had ${criteria.size}" }
            require(criteria.keys.none { it.isBlank() }) { "A choice option key cannot be blank" }
        }

        /** The option keys, in declared order. */
        val options: List<String>
            get() = criteria.keys.toList()

        override val answerKeys: List<String>
            get() = options

        override fun describe(key: String): String? = criteria[key]
    }

    /**
     * An ordered rubric. A decision model returns a probability per level and a
     * probability-weighted score.
     *
     * [criteria] is the ordered list of level descriptions, lowest first. Levels
     * are addressed by their index as a string (`"0"`, `"1"`, …), which is the key
     * the hosted providers answer with and the key a [Judgment] carries.
     */
    @Serializable
    @SerialName("score")
    data class Score(
        override val instructions: String,
        val criteria: List<String>,
    ) : Question {

        init {
            require(instructions.isNotBlank()) { "A score needs instructions" }
            require(criteria.size >= 2) { "A score needs at least two levels, had ${criteria.size}" }
        }

        /** The level keys, lowest first. */
        val levels: List<String>
            get() = criteria.indices.map { it.toString() }

        override val answerKeys: List<String>
            get() = levels

        override fun describe(key: String): String? = key.toIntOrNull()?.let { criteria.getOrNull(it) }
    }
}

/** The wire name of this question's type: `noul`, `choice` or `score`. */
val Question.typeName: String
    get() = when (this) {
        is Question.Noul -> "noul"
        is Question.Choice -> "choice"
        is Question.Score -> "score"
    }
