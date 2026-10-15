package link.socket.ampere.eval.decide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient

/**
 * One labelled state for a decision client to judge (AMPR-422).
 *
 * A case is the decision-seam analogue of an `EvalCase`: instead of a seed that drives an Arc,
 * it is a [state] already rendered as a decision call's text, the [question] asked about it, and
 * the answer key a correct client gives. The label is [expected], and it is set by whoever
 * constructed the state — a corpus built by construction knows its own labels, which is what
 * makes a failure on one of its cases a real failure rather than a disagreement with a grader.
 *
 * @property id Unique within a corpus. A recorded answer is filed under it.
 * @property kind The construction rule this case was built by (`clean`, `partial`, …). The
 *   report breaks results down by it, so the weakest kind for an arm can be named.
 * @property state The text the client is asked about, exactly as the production caller would
 *   render it. For the goal-met corpus that is `RunObservation.asDecisionState`.
 * @property question The question asked. Its serialized form is the wire body.
 * @property expected The one key in [Question.answerKeys] a correct client answers with.
 * @property family A coarser grouping than [kind] — for the goal-met corpus, the goal family
 *   the case was rendered from. Null when a corpus has no second axis.
 */
@Serializable
data class DecisionCase(
    val id: String,
    val kind: String,
    val state: String,
    val question: Question,
    val expected: String,
    val family: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A decision case needs an id" }
        require(kind.isNotBlank()) { "A decision case needs a kind" }
        require(expected in question.answerKeys) {
            "Case '$id' expects '$expected', which is not one of the question's answers ${question.answerKeys}"
        }
    }
}

/**
 * One client under measurement, with the configuration it is handed on every call.
 *
 * An arm is named so a report can put two of them side by side: the same corpus through the
 * any-success rule and through a hosted decision model is two arms, and the report is the
 * comparison. The [configuration] is what a hosted run's seat would pass; a hosted System One
 * adapter ignores it and the model-backed one routes by it.
 */
data class DecisionArm(
    val id: String,
    val client: UpstreamDecisionClient,
    val configuration: AIConfiguration,
) {
    init {
        require(id.isNotBlank()) { "A decision arm needs an id" }
    }
}

/**
 * The JSON a corpus of [DecisionCase]s and a file of [DecisionRecord]s are written with.
 *
 * `type` is the discriminator because that is what a [Question]'s wire format calls it, and
 * `encodeDefaults` so an omitted `family` is written as `null` rather than disappearing — a
 * committed corpus should be readable without knowing which fields had defaults. Compact: a
 * recorded-answers file is one record per line, and a pretty printer would break that.
 */
val DecisionCaseJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    prettyPrint = false
    ignoreUnknownKeys = true
}

/**
 * [DecisionCaseJson], pretty-printed, for the committed corpus file — a reviewer reads a
 * corpus diff, and a one-line 300-case file has no reviewable diff.
 */
val DecisionCorpusJson: Json = Json(DecisionCaseJson) {
    prettyPrint = true
}
