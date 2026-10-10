package link.socket.ampere.llm.decide

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * The hosted System One adapter (AMPR-384, J2): one Ktor POST, no provider SDK.
 *
 * Jev (TypeSafe, through OpenRouter) and Clef / Clef-flash (Cloudflare Workers
 * AI) read the same body — `{state, questions}` — and answer with the same
 * `answers` object, so one adapter serves all three. What differs is adapter
 * configuration, not type: the endpoint, the credential, and whatever extra
 * fields the body wants (`"model": "typesafe/jev-1.13"` for OpenRouter, a bare
 * `"model": "clef"` on Workers AI).
 *
 * Two response envelopes are accepted: the bare System One body, and
 * Cloudflare's REST envelope `{"result": <body>, "success": true}`.
 *
 * Every judgment this adapter returns is [ConfidenceSource.MEASURED] with a
 * distribution, and ran in the [InferenceLocality.CLOUD]. The state leaves the
 * device: which provider may receive judgment state from a shipped consumer
 * is an open question on the epic (OQ2), not something this class decides.
 *
 * @param endpoint The full URL to POST to.
 * @param credential Sent as `Authorization: Bearer <credential>`.
 * @param model Which model the endpoint serves. The response's own `model`
 *   string, when present, lands on [ModelSnapshot.revision].
 * @param bodyExtras Fields merged into the request body before `state` and
 *   `questions`; those two always win.
 * @param httpClient The client to POST with. The default discovers the
 *   platform engine; tests pass one over a mock engine.
 */
@AmpereStableApi
class HostedSystemOneDecisionClient(
    private val endpoint: String,
    private val credential: String,
    private val model: ModelSnapshot,
    private val bodyExtras: Map<String, JsonElement> = emptyMap(),
    private val httpClient: HttpClient = HttpClient(),
) : UpstreamDecisionClient {

    override suspend fun decide(
        request: DecisionRequest,
        configuration: AIConfiguration,
    ): DecisionResponse {
        val body = buildJsonObject {
            bodyExtras.forEach { (key, value) -> put(key, value) }
            put("state", request.state)
            put("questions", DecisionJson.encodeToJsonElement(QuestionsSerializer, request.questions))
        }

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $credential")
            contentType(ContentType.Application.Json)
            setBody(DecisionJson.encodeToString(JsonObject.serializer(), body))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw MalformedDecisionResponseException(
                "Decision endpoint answered ${response.status.value}: ${text.take(ERROR_BODY_PREVIEW)}",
            )
        }
        return parse(text, request)
    }

    /** Parse a System One response body (bare or Cloudflare-wrapped) against the questions asked. */
    internal fun parse(text: String, request: DecisionRequest): DecisionResponse {
        val envelope = runCatching { DecisionJson.parseToJsonElement(text).jsonObject }
            .getOrElse {
                throw MalformedDecisionResponseException("Decision endpoint did not answer with a JSON object")
            }
        val payload = unwrap(envelope)
        val answers = payload["answers"]?.let { it as? JsonObject }
            ?: throw MalformedDecisionResponseException("Decision response has no 'answers' object")
        val snapshot = payload["model"]?.stringOrNull()?.let { model.copy(revision = it) } ?: model

        val judgments = request.questions.mapValues { (id, question) ->
            val answer = answers[id]?.let { it as? JsonObject }
                ?: throw MalformedDecisionResponseException("Decision response has no answer for question '$id'")
            judgmentFor(id, question, answer, snapshot)
        }
        return DecisionResponse(judgments = judgments, usage = usageOf(payload["usage"]))
    }

    private fun unwrap(envelope: JsonObject): JsonObject {
        val result = envelope["result"] as? JsonObject ?: return envelope
        val success = envelope["success"]?.let { (it as? JsonPrimitive)?.booleanOrNull }
        if (success == false) {
            throw MalformedDecisionResponseException("Decision endpoint reported success=false: ${envelope["errors"]}")
        }
        return result
    }

    private fun judgmentFor(
        id: String,
        question: Question,
        answer: JsonObject,
        snapshot: ModelSnapshot,
    ): Judgment = when (question) {
        is Question.Noul -> {
            val pTrue = answer["noul"]?.doubleOrNullLenient()
                ?: throw MalformedDecisionResponseException("Noul '$id' has no 'noul' probability")
            if (pTrue !in 0.0..1.0) {
                throw MalformedDecisionResponseException("Noul '$id' probability out of range: $pTrue")
            }
            val key = if (pTrue >= 0.5) Question.Noul.TRUE else Question.Noul.FALSE
            measured(key, mapOf(Question.Noul.TRUE to pTrue, Question.Noul.FALSE to 1.0 - pTrue), snapshot)
        }

        is Question.Choice -> {
            val pick = answer["choice"]?.stringOrNull()
                ?: throw MalformedDecisionResponseException("Choice '$id' has no 'choice'")
            if (pick !in question.answerKeys) {
                throw MalformedDecisionResponseException(
                    "Choice '$id' answered '$pick', which is not one of ${question.answerKeys}",
                )
            }
            val probabilities = probabilitiesOf(answer["probabilities"], question)
            Judgment(
                answer = pick,
                distribution = probabilities,
                source = ConfidenceSource.MEASURED,
                modelSnapshot = snapshot,
                locality = InferenceLocality.CLOUD,
                confidence = probabilities?.get(pick) ?: answer["confidence"]?.doubleOrNullLenient(),
            )
        }

        is Question.Score -> {
            val probabilities = probabilitiesOf(answer["probabilities"], question)
            if (probabilities != null) {
                // Argmax, lowest level on a tie: the level the mass favours, not the expectation.
                val top = question.levels.maxByOrNull { probabilities[it] ?: 0.0 }
                    ?: throw MalformedDecisionResponseException("Score '$id' has no levels")
                measured(top, probabilities, snapshot)
            } else {
                val score = answer["score"]?.doubleOrNullLenient()
                    ?: throw MalformedDecisionResponseException("Score '$id' has neither 'probabilities' nor 'score'")
                val level = score.roundToInt().coerceIn(0, question.criteria.lastIndex).toString()
                Judgment(
                    answer = level,
                    distribution = null,
                    source = ConfidenceSource.MEASURED,
                    modelSnapshot = snapshot,
                    locality = InferenceLocality.CLOUD,
                    confidence = answer["confidence"]?.doubleOrNullLenient(),
                )
            }
        }
    }

    private fun measured(answer: String, distribution: Map<String, Double>, snapshot: ModelSnapshot): Judgment =
        Judgment(
            answer = answer,
            distribution = distribution,
            source = ConfidenceSource.MEASURED,
            modelSnapshot = snapshot,
            locality = InferenceLocality.CLOUD,
        )

    /**
     * The per-answer probabilities, restricted to the keys the question declared
     * and padded with zero for any the provider omitted. Null when absent.
     */
    private fun probabilitiesOf(element: JsonElement?, question: Question): Map<String, Double>? {
        val reported = element as? JsonObject ?: return null
        return question.answerKeys.associateWith { key ->
            reported[key]?.doubleOrNullLenient() ?: 0.0
        }
    }

    private fun usageOf(element: JsonElement?): TokenUsage {
        val usage = element as? JsonObject ?: return TokenUsage()
        return TokenUsage(
            inputTokens = usage["input_tokens"]?.let { (it as? JsonPrimitive)?.intOrNull },
            outputTokens = usage["output_tokens"]?.let { (it as? JsonPrimitive)?.intOrNull },
            estimatedCost = usage["cost"]?.doubleOrNullLenient(),
        )
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.doubleOrNullLenient(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    private companion object {
        const val ERROR_BODY_PREVIEW = 200
        val QuestionsSerializer = MapSerializer(String.serializer(), Question.serializer())
    }
}
