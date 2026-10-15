package link.socket.ampere.eval.decide

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.llm.decide.BandFitGuard
import link.socket.ampere.llm.decide.Judgment
import link.socket.ampere.llm.decide.ModelSnapshot

/**
 * What one arm answered to one case on one repeat (AMPR-422).
 *
 * This is the unit the bench records and the unit a recorded-answers file holds, one per
 * line. It carries the whole [Judgment] flattened — the answer, the distribution when the
 * adapter measured one, the source, the snapshot, the locality — plus what the bench observed
 * around the call: how long it took and what it used. A call that threw is a record too, with
 * [answer] null and [error] set, because a transport that cannot answer a question is a
 * finding, not a gap in the data.
 *
 * [stateDigest] is the same digest a `JudgmentRecorded` event carries, so a recorded answer
 * can be checked against the case it claims to answer when the corpus is regenerated.
 *
 * @property repeat Zero-based. The same case is asked [DecisionBench.run]'s `repeats` times
 *   of every arm, and repeat stability is read off these.
 * @property latencyMs Wall time around the client's `decide`, as the bench measured it.
 * @property usage What the response reported, or an empty [TokenUsage] when it reported none.
 */
@Serializable
data class DecisionRecord(
    val caseId: String,
    val armId: String,
    val repeat: Int,
    val stateDigest: String,
    val answer: String?,
    val distribution: Map<String, Double>? = null,
    val confidence: Double? = null,
    val source: ConfidenceSource? = null,
    val modelSnapshot: ModelSnapshot? = null,
    val locality: InferenceLocality? = null,
    val latencyMs: Long = 0L,
    val usage: TokenUsage = TokenUsage(),
    val error: String? = null,
) {
    init {
        require(repeat >= 0) { "A repeat index is zero-based and not negative; got $repeat" }
        require(answer != null || error != null) {
            "Record for case '$caseId' on arm '$armId' has neither an answer nor an error"
        }
    }

    /** True when the call returned a judgment; false when it threw. */
    val answered: Boolean
        get() = answer != null

    /**
     * The judgment this record was flattened from, or null when the call threw.
     *
     * Rebuilt the way [BandFitGuard] will read it: a measured source with a distribution is
     * fittable, anything else is not.
     */
    fun toJudgment(): Judgment? {
        val key = answer ?: return null
        return Judgment(
            answer = key,
            distribution = distribution,
            source = source ?: ConfidenceSource.SELF_REPORTED,
            modelSnapshot = modelSnapshot ?: ModelSnapshot(providerId = "unknown", modelId = "unknown"),
            locality = locality ?: InferenceLocality.CLOUD,
            confidence = confidence,
        )
    }

    /** True when [BandFitGuard.isFittable] accepts the judgment this record holds. */
    val fittable: Boolean
        get() = toJudgment()?.let(BandFitGuard::isFittable) ?: false

    companion object {

        /** [judgment] as the record of [caseId] on [armId], repeat [repeat]. */
        fun of(
            caseId: String,
            armId: String,
            repeat: Int,
            stateDigest: String,
            judgment: Judgment,
            latencyMs: Long,
            usage: TokenUsage,
        ): DecisionRecord = DecisionRecord(
            caseId = caseId,
            armId = armId,
            repeat = repeat,
            stateDigest = stateDigest,
            answer = judgment.answer,
            distribution = judgment.distribution,
            confidence = judgment.confidence,
            source = judgment.source,
            modelSnapshot = judgment.modelSnapshot,
            locality = judgment.locality,
            latencyMs = latencyMs,
            usage = usage,
        )

        /** The record of a call that threw [error]. */
        fun failed(
            caseId: String,
            armId: String,
            repeat: Int,
            stateDigest: String,
            error: String,
            latencyMs: Long,
        ): DecisionRecord = DecisionRecord(
            caseId = caseId,
            armId = armId,
            repeat = repeat,
            stateDigest = stateDigest,
            answer = null,
            latencyMs = latencyMs,
            error = error,
        )

        /** One record per line, in [DecisionCaseJson]; the recorded-answers file format. */
        fun encodeLines(records: List<DecisionRecord>): String =
            records.joinToString(separator = "\n", postfix = "\n") {
                DecisionCaseJson.encodeToString(serializer(), it)
            }

        /** The inverse of [encodeLines]. Blank lines are skipped. */
        fun decodeLines(text: String): List<DecisionRecord> = text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { DecisionCaseJson.decodeFromString(serializer(), it) }
            .toList()
    }
}
