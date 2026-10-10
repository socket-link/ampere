package link.socket.ampere.llm.decide

import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * Answers a [Question] about a state with one of its [Question.answerKeys].
 *
 * The function returns the key rather than a whole [Judgment] so the adapter,
 * not every judge, is what makes the result [ConfidenceSource.MEASURED] with a
 * one-hot distribution. A function cannot be unsure; its judgment says so by
 * construction.
 */
typealias DeterministicJudge = (state: String, question: Question) -> String

/**
 * The deterministic adapter (AMPR-384, J2): plain code as a decision model.
 *
 * Every judgment is [ConfidenceSource.MEASURED] with all its mass on the
 * answer, and ran [InferenceLocality.ON_DEVICE] — nothing left the process.
 * This is how an existing heuristic becomes an explicit judgment with a
 * record: when J9 moves goal completion onto the Decide seam, today's
 * "any success" rule is a [DeterministicJudge], and a run with no decision
 * transport bound reproduces today's behaviour on purpose rather than by
 * omission.
 *
 * @param name Names the function on [ModelSnapshot.modelId], so a record
 *   shows which rule answered.
 * @param judge The function.
 */
@AmpereStableApi
class DeterministicDecisionClient(
    private val name: String,
    private val judge: DeterministicJudge,
) : UpstreamDecisionClient {

    private val snapshot = ModelSnapshot(providerId = PROVIDER_ID, modelId = name)

    override suspend fun decide(
        request: DecisionRequest,
        configuration: AIConfiguration,
    ): DecisionResponse {
        val judgments = request.questions.mapValues { (id, question) ->
            val answer = judge(request.state, question)
            if (answer !in question.answerKeys) {
                throw MalformedDecisionResponseException(
                    "Deterministic judge '$name' answered question '$id' with '$answer', " +
                        "which is not one of ${question.answerKeys}",
                )
            }
            Judgment.oneHot(
                question = question,
                answer = answer,
                modelSnapshot = snapshot,
                locality = InferenceLocality.ON_DEVICE,
            )
        }
        return DecisionResponse(judgments = judgments)
    }

    companion object {
        /** The provider id every deterministic judgment carries. */
        const val PROVIDER_ID: String = "deterministic"
    }
}
