package link.socket.ampere.llm.decide

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * The hosted adapter against recorded System One response shapes: Jev through
 * OpenRouter (the published examples) and Clef on Workers AI (the same body
 * inside Cloudflare's REST envelope).
 */
class HostedSystemOneDecisionClientTest {

    private val configuration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    private val team = Question.Choice(
        instructions = "Which team should handle `ticket`?",
        criteria = linkedMapOf(
            "billing" to "Charges, invoices, and refunds",
            "technical" to "Bugs, outages, and broken features",
            "account" to "Login, password, and profile changes",
        ),
    )
    private val urgency = Question.Score(
        instructions = "How urgent is `ticket`?",
        criteria = listOf(
            "No deadline; routine question",
            "Customer wants a fix soon but nothing is blocked",
            "Customer names a deadline or something is blocked now",
        ),
    )
    private val refund = Question.Noul.of(
        instructions = "Is the customer in `ticket` asking for money back?",
        whenTrue = "The customer wants a charge reversed",
        whenFalse = "The customer wants something else",
    )
    private val request = DecisionRequest(
        state = """{"ticket": "Hi, I was charged twice for my Pro subscription this month."}""",
        questions = mapOf("team" to team, "urgency" to urgency, "refund" to refund),
    )

    private val jevResponse = """
        {
          "model": "typesafe/jev-1.13-20260917",
          "answers": {
            "team": {
              "type": "choice",
              "choice": "billing",
              "probabilities": { "technical": 0, "billing": 1, "account": 0 },
              "confidence": 1
            },
            "urgency": {
              "type": "score",
              "score": 1.15,
              "legend": { "0": "No deadline", "1": "Soon", "2": "Blocked" },
              "probabilities": { "0": 0, "1": 0.85, "2": 0.15 },
              "confidence": 0.77
            },
            "refund": { "type": "noul", "noul": 0.82 }
          },
          "usage": { "input_tokens": 447, "output_tokens": 69, "cost": 0.000018774 },
          "id": "gen-dec-1790013867-chEjwDPvoiiffM3J3eDF",
          "provider": "TypeSafe"
        }
    """.trimIndent()

    private class Captured(var body: String? = null, var authorization: String? = null, var url: String? = null)

    private fun client(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        captured: Captured = Captured(),
        bodyExtras: Map<String, JsonPrimitive> = mapOf("model" to JsonPrimitive("typesafe/jev-1.13")),
    ): HostedSystemOneDecisionClient {
        val engine = MockEngine { call ->
            captured.body = call.body.toByteArray().decodeToString()
            captured.authorization = call.headers[HttpHeaders.Authorization]
            captured.url = call.url.toString()
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HostedSystemOneDecisionClient(
            endpoint = "https://openrouter.ai/api/alpha/decisions",
            credential = "sk-test",
            model = ModelSnapshot(providerId = "openrouter", modelId = "typesafe/jev-1.13"),
            bodyExtras = bodyExtras,
            httpClient = HttpClient(engine),
        )
    }

    @Test
    fun `a Jev response yields a measured judgment per question with the reported usage`() = runTest {
        val response = client(jevResponse).decide(request, configuration)

        val team = response.judgments.getValue("team")
        assertEquals("billing", team.answer)
        assertEquals(mapOf("billing" to 1.0, "technical" to 0.0, "account" to 0.0), team.distribution)
        assertEquals(1.0, team.confidence)
        assertEquals(ConfidenceSource.MEASURED, team.source)
        assertEquals(InferenceLocality.CLOUD, team.locality)
        assertEquals(
            ModelSnapshot(
                providerId = "openrouter",
                modelId = "typesafe/jev-1.13",
                revision = "typesafe/jev-1.13-20260917",
            ),
            team.modelSnapshot,
        )

        val urgency = response.judgments.getValue("urgency")
        assertEquals("1", urgency.answer)
        assertEquals(0.85, urgency.confidence)
        assertEquals(1.15, assertNotNull(urgency.expectedScore()), absoluteTolerance = 1e-9)

        val refund = response.judgments.getValue("refund")
        assertEquals("true", refund.answer)
        assertEquals(0.82, refund.confidence)
        assertEquals(0.18, assertNotNull(refund.distribution).getValue("false"), absoluteTolerance = 1e-9)

        assertEquals(447, response.usage.inputTokens)
        assertEquals(69, response.usage.outputTokens)
        assertEquals(0.000018774, response.usage.estimatedCost)
    }

    @Test
    fun `the request is one authenticated POST of the wire body with the extras merged in`() = runTest {
        val captured = Captured()
        client(jevResponse, captured = captured).decide(request, configuration)

        assertEquals("Bearer sk-test", captured.authorization)
        assertEquals("https://openrouter.ai/api/alpha/decisions", captured.url)
        val body = Json.parseToJsonElement(assertNotNull(captured.body)).jsonObject
        assertEquals("typesafe/jev-1.13", body.getValue("model").jsonPrimitive.content)
        assertEquals(request.state, body.getValue("state").jsonPrimitive.content)
        val questions = body.getValue("questions").jsonObject
        assertEquals("choice", questions.getValue("team").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "Charges, invoices, and refunds",
            questions.getValue("team").jsonObject.getValue("criteria").jsonObject
                .getValue("billing").jsonPrimitive.content,
        )
        assertEquals("noul", questions.getValue("refund").jsonObject.getValue("type").jsonPrimitive.content)
        assertNotNull(questions.getValue("refund").jsonObject["criteria"])
    }

    @Test
    fun `a Cloudflare REST envelope is unwrapped`() = runTest {
        val wrapped = """{"result": $jevResponse, "success": true, "errors": [], "messages": []}"""

        val clef = client(wrapped, bodyExtras = mapOf("model" to JsonPrimitive("clef")))
        val response = clef.decide(request, configuration)

        assertEquals("billing", response.judgments.getValue("team").answer)
        assertEquals(447, response.usage.inputTokens)
    }

    @Test
    fun `a non-success status is a malformed response and never a judgment`() = runTest {
        assertFailsWith<MalformedDecisionResponseException> {
            client("""{"error": "unauthorized"}""", status = HttpStatusCode.Unauthorized).decide(request, configuration)
        }
    }

    @Test
    fun `a choice outside the declared options is a malformed response`() = runTest {
        val body = """{"answers": {"team": {"type": "choice", "choice": "sales", "probabilities": {"sales": 1}}}}"""

        assertFailsWith<MalformedDecisionResponseException> {
            client(body).decide(DecisionRequest("s", mapOf("team" to team)), configuration)
        }
    }

    @Test
    fun `a missing answer for an asked question is a malformed response`() = runTest {
        val body = """{"answers": {"team": {"type": "choice", "choice": "billing"}}}"""

        assertFailsWith<MalformedDecisionResponseException> {
            client(body).decide(request, configuration)
        }
    }

    @Test
    fun `a choice answered without probabilities has no distribution`() = runTest {
        val body = """{"answers": {"team": {"type": "choice", "choice": "billing", "confidence": 0.6}}}"""

        val response = client(body).decide(DecisionRequest("s", mapOf("team" to team)), configuration)

        val judgment = response.judgments.getValue("team")
        assertNull(judgment.distribution)
        assertEquals(0.6, judgment.confidence)
        assertNull(response.usage.inputTokens)
    }
}
