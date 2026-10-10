package link.socket.ampere.llm.decide

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.api.AmpereStableApi

/**
 * Which model produced a judgment (AMPR-384, J3).
 *
 * [providerId] and [modelId] are the two facts every telemetry event already
 * carries. [revision] is the dated or hashed build a hosted provider reports
 * back (Jev answers `typesafe/jev-1.13-20260917` for a request that named
 * `typesafe/jev-1.13`); null when the provider reports none.
 */
@AmpereStableApi
@Serializable
data class ModelSnapshot(
    val providerId: String,
    val modelId: String,
    val revision: String? = null,
)

/**
 * A decision model's answer to one [Question] (AMPR-384, J3).
 *
 * @property answer One of the question's [Question.answerKeys].
 * @property distribution Probability per declared answer, when the adapter
 *   measured one. Only a System One provider or the deterministic adapter has
 *   one; a generative model asked for JSON does not (the J4 amendment), so this
 *   is null there and [source] says so.
 * @property source Whether [confidence] was measured by a decision model or
 *   reported by a generative model about itself. The two are different
 *   quantities; no band is ever fitted on a [ConfidenceSource.SELF_REPORTED]
 *   value (see [BandFitGuard]).
 * @property modelSnapshot Which model answered.
 * @property locality Where it ran. [InferenceLocality.ON_DEVICE] only when
 *   nothing left the process; anything not provably local is
 *   [InferenceLocality.CLOUD].
 * @property confidence `p(answer)`. For a measured judgment this is the
 *   distribution's mass on [answer]; for a self-reported one it is the F10
 *   mapping of the level the model gave (`low/medium/high → 0.25/0.5/0.75`);
 *   null when nothing reported anything.
 */
@AmpereStableApi
@Serializable
data class Judgment(
    val answer: String,
    val distribution: Map<String, Double>?,
    val source: ConfidenceSource,
    val modelSnapshot: ModelSnapshot,
    val locality: InferenceLocality,
    val confidence: Double? = distribution?.get(answer),
) {
    init {
        require(answer.isNotBlank()) { "A judgment needs an answer" }
        distribution?.let { measured ->
            require(measured.containsKey(answer)) {
                "A judgment's distribution must cover its answer '$answer', had keys ${measured.keys}"
            }
            measured.forEach { (key, probability) ->
                require(probability in 0.0..1.0) { "Probability of '$key' must be in [0, 1], was $probability" }
            }
        }
        confidence?.let { require(it in 0.0..1.0) { "Confidence must be in [0, 1], was $it" } }
    }

    /**
     * The probability-weighted score of a [Question.Score] judgment: the sum of
     * each level's index times its probability. Null when there is no
     * distribution or its keys are not level indices.
     */
    fun expectedScore(): Double? {
        val measured = distribution ?: return null
        var total = 0.0
        for ((key, probability) in measured) {
            val level = key.toIntOrNull() ?: return null
            total += level * probability
        }
        return total
    }

    companion object {
        /**
         * A measured judgment whose whole mass sits on [answer]: the shape the
         * deterministic adapter produces, and the honest distribution of a
         * function that cannot be unsure.
         */
        fun oneHot(
            question: Question,
            answer: String,
            modelSnapshot: ModelSnapshot,
            locality: InferenceLocality,
        ): Judgment {
            require(answer in question.answerKeys) {
                "Answer '$answer' is not one of the question's declared answers ${question.answerKeys}"
            }
            return Judgment(
                answer = answer,
                distribution = question.answerKeys.associateWith { if (it == answer) 1.0 else 0.0 },
                source = ConfidenceSource.MEASURED,
                modelSnapshot = modelSnapshot,
                locality = locality,
            )
        }
    }
}
