package link.socket.ampere.llm.decide

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.reasoning.Confidence
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.reasoning.LLMResponseParser
import link.socket.ampere.agents.domain.reasoning.withFloorAtLeast
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.InferenceLocalityClassifier
import link.socket.ampere.agents.execution.dataBlock
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * The model-backed adapter (AMPR-384, J2): each question rendered as a prompt
 * to the agent's generative model through [AgentLLMService], answered as JSON.
 *
 * This keeps a consumer working where no decision provider is configured, but
 * it is not a decision model and the judgments say so: a generative model has
 * no probability channel, so [Judgment.distribution] is null and
 * [Judgment.source] is [ConfidenceSource.SELF_REPORTED]. The `low | medium |
 * high` the model reports about itself lands on [Judgment.confidence] through
 * the F10 mapping (J5); it is never fitted as a band.
 *
 * It is the two halves of `AgentLLMService.callForJson` kept apart —
 * [AgentLLMService.callDetailed] then [LLMResponseParser.cleanJsonResponse] —
 * so the snapshot names the provider and model that actually answered after
 * routing, not the configuration the caller held.
 *
 * Nothing falls back to this adapter. A consumer names it.
 *
 * ## Never on the device
 *
 * A judgment is never display text, so it never runs on the on-device model,
 * which serves rung ZERO only (AMPR-372). Measured through this adapter, that
 * model called 59.0% of unmet runs met, against 15.0% for a rung-`THREE` cloud
 * model on the same prompts, while every one of its replies parsed. A
 * requirement on [routingContext] therefore has its floor raised to rung `ONE`
 * before it is forwarded, which leaves the device out of the candidates
 * (AMPR-459); a stricter floor stands. A context with no requirement is
 * forwarded as it is, so the judgment keeps the configured model: adding a
 * requirement would turn on cheapest-capable routing, and that is a routing
 * change, not a refusal.
 *
 * @param llmService The generative path. Its relay, transport and telemetry
 *   apply unchanged; every question is one `ProviderCall*` pair.
 * @param routingContext Routing context for each generative call, or null. Its
 *   requirement, if it has one, is raised to rung `ONE` (see above).
 * @param localityClassifier Says where the answering model ran. Null labels
 *   every judgment [InferenceLocality.CLOUD], the safe direction.
 */
@AmpereStableApi
class ModelBackedDecisionClient(
    private val llmService: AgentLLMService,
    routingContext: RoutingContext? = null,
    private val localityClassifier: InferenceLocalityClassifier? = null,
) : UpstreamDecisionClient {

    /** The constructor's context as every call forwards it: its requirement off rung ZERO. */
    private val routingContext: RoutingContext? = routingContext?.let { context ->
        context.requirements
            ?.let { requirement -> context.copy(requirements = requirement.withFloorAtLeast(CapabilityRung.ONE)) }
            ?: context
    }

    override suspend fun decide(
        request: DecisionRequest,
        configuration: AIConfiguration,
    ): DecisionResponse {
        val judgments = request.questions.mapValues { (id, question) ->
            val result = llmService.callDetailed(
                prompt = renderPrompt(request.state, question),
                systemMessage = SYSTEM_MESSAGE,
                routingContext = routingContext,
            )
            val answer = LLMResponseParser.parseJsonObject(LLMResponseParser.cleanJsonResponse(result.text))

            val key = normaliseAnswer(answer["answer"]?.let { it as? JsonPrimitive }, question)
                ?: throw MalformedDecisionResponseException(
                    "Model answered question '$id' with ${answer["answer"]}, " +
                        "which is not one of ${question.answerKeys}",
                )
            val reportedLevel = answer["confidence"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val level = Confidence.parseOrNull(reportedLevel)
            val locality = localityClassifier?.localityOf(result.providerId, result.modelId) ?: InferenceLocality.CLOUD

            Judgment(
                answer = key,
                distribution = null,
                source = ConfidenceSource.SELF_REPORTED,
                modelSnapshot = ModelSnapshot(providerId = result.providerId, modelId = result.modelId),
                locality = locality,
                confidence = level?.asProbability(),
            )
        }
        // Usage was booked on each call's ProviderCallCompletedEvent; nothing further to report here.
        return DecisionResponse(judgments = judgments)
    }

    private fun normaliseAnswer(primitive: JsonPrimitive?, question: Question): String? {
        val reported = primitive ?: return null
        val raw = reported.contentOrNull?.trim() ?: return null
        if (raw in question.answerKeys) return raw
        if (question is Question.Noul) {
            reported.booleanOrNull?.let { return if (it) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY }
            when (raw.lowercase()) {
                "true", "yes" -> return Question.Noul.TRUE_KEY
                "false", "no" -> return Question.Noul.FALSE_KEY
            }
        }
        return question.answerKeys.firstOrNull { it.equals(raw, ignoreCase = true) }
    }

    internal fun renderPrompt(state: String, question: Question): String = buildString {
        appendLine("Answer one typed question about the state below.")
        appendLine("Reply with JSON only, of the form")
        appendLine("{\"answer\": <key>, \"confidence\": \"low\" | \"medium\" | \"high\"}.")
        appendLine("The answer must be exactly one of the allowed keys.")
        appendLine()
        appendLine("State:")
        // AMPR-453: a state is data — a hosted run's carries its step results, which are
        // tool output — so it reaches the model inside the one delimited data block.
        append(dataBlock(state))
        appendLine()
        appendLine("Question (${question.typeName}): ${question.instructions}")
        appendLine("Allowed answers:")
        question.answerKeys.forEach { key ->
            appendLine("- \"$key\": ${question.describe(key)}")
        }
    }

    private companion object {
        const val SYSTEM_MESSAGE =
            "You are a decision function. You answer typed questions about a state with one of the declared " +
                "answers and your own confidence. You output JSON and nothing else."
    }
}
