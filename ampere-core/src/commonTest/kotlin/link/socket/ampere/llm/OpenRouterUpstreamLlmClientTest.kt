package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter

/**
 * The OpenRouter transport (AMPR-437) over a mock engine. The response bodies
 * follow the chat response schema of OpenRouter's generated SDK
 * (`@openrouter/sdk` 1.4.25): OpenAI's shape, plus `usage.cost` and, when the
 * request opts in, `openrouter_metadata` naming the host that served the call.
 */
class OpenRouterUpstreamLlmClientTest {

    private val routed = AIConfiguration_Default(AIProvider_OpenRouter, AIModel_Claude.Sonnet_5)

    private val request = ChatCompletionRequest(
        model = ModelId("anthropic/claude-sonnet-5"),
        messages = listOf(ChatMessage(role = ChatRole.User, content = "Hello")),
        temperature = 0.2,
    )

    private fun response(host: String? = "anthropic", cost: Double = 0.00042): String {
        val metadata = host?.let {
            """
            ,"openrouter_metadata": {
              "attempt": 1,
              "attempts": [{ "model": "anthropic/claude-sonnet-5", "provider": "$it", "status": 200 }],
              "endpoints": { "available": [{ "model": "anthropic/claude-sonnet-5", "provider": "$it", "selected": true }], "total": 1 },
              "is_byok": false,
              "region": null,
              "requested": "anthropic/claude-sonnet-5",
              "strategy": "direct",
              "summary": "served"
            }
            """.trimIndent()
        } ?: ""
        return """
            {
              "id": "gen-123",
              "object": "chat.completion",
              "created": 1760227200,
              "model": "anthropic/claude-sonnet-5",
              "system_fingerprint": null,
              "choices": [{
                "index": 0,
                "message": { "role": "assistant", "content": "Hi there" },
                "finish_reason": "stop"
              }],
              "usage": { "prompt_tokens": 9, "completion_tokens": 3, "total_tokens": 12, "cost": $cost }
              $metadata
            }
        """.trimIndent()
    }

    private class Captured {
        var body: JsonObject? = null
        var headers: Headers? = null
        var url: String? = null
    }

    private fun client(
        captured: Captured = Captured(),
        policy: OpenRouterRoutingPolicy = OpenRouterRoutingPolicy(),
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = response(),
    ): OpenRouterUpstreamLlmClient {
        val engine = MockEngine { call ->
            captured.url = call.url.toString()
            captured.headers = call.headers
            captured.body = Json.parseToJsonElement(call.body.toByteArray().decodeToString()).jsonObject
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return OpenRouterUpstreamLlmClient(policy, HttpClient(engine))
    }

    // Task 2: the gap the bundled transport cannot cross.

    @Test
    fun `the bundled request and response types carry no routing preference or serving host or cost`() {
        val json = Json { ignoreUnknownKeys = true }

        // ChatCompletionRequest has no `provider` parameter, and its serializer writes none.
        val sent = json.encodeToJsonElement(ChatCompletionRequest.serializer(), request).jsonObject
        assertFalse("provider" in sent)

        // What the bundled client hands back, re-encoded: the host and the cost are gone.
        val decoded = json.decodeFromString(ChatCompletion.serializer(), response())
        val kept = json.encodeToJsonElement(ChatCompletion.serializer(), decoded).jsonObject
        assertFalse("openrouter_metadata" in kept)
        assertFalse("cost" in kept.getValue("usage").jsonObject)
    }

    // Task 3: the policy.

    @Test
    fun `the default policy pins to the maker and forbids fallback and denies data collection`() {
        val provider = OpenRouterRoutingPolicy().providerObject("anthropic")

        assertEquals(JsonArray(listOf(JsonPrimitive("anthropic"))), provider["order"])
        assertEquals(JsonArray(listOf(JsonPrimitive("anthropic"))), provider["only"])
        assertEquals(JsonPrimitive(false), provider["allow_fallbacks"])
        assertEquals(JsonPrimitive("deny"), provider["data_collection"])
    }

    @Test
    fun `google is pinned to both of its own hosting platforms`() {
        val provider = OpenRouterRoutingPolicy().providerObject("google")

        assertEquals(
            listOf("google-ai-studio", "google-vertex"),
            provider.getValue("only").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `an unpinned permissive policy names no host`() {
        val provider = OpenRouterRoutingPolicy(
            pinToMaker = false,
            allowFallbacks = true,
            denyDataCollection = false,
        ).providerObject("mistral")

        assertNull(provider["order"])
        assertNull(provider["only"])
        assertEquals(JsonPrimitive(true), provider["allow_fallbacks"])
        assertEquals(JsonPrimitive("allow"), provider["data_collection"])
    }

    @Test
    fun `pinning a maker with no known host is refused and names the maker`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            OpenRouterRoutingPolicy().providerObject("mistral")
        }
        assertTrue("mistral" in failure.message.orEmpty())
    }

    // Task 4: the transport.

    @Test
    fun `the posted body carries the wire id and the three policy fields`() = runTest {
        val captured = Captured()

        client(captured).callDetailed(request, routed)

        val body = assertNotNull(captured.body)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", captured.url)
        assertEquals(JsonPrimitive("anthropic/claude-sonnet-5"), body["model"])
        assertEquals(JsonPrimitive(0.2), body["temperature"])
        val provider = body.getValue("provider").jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("anthropic"))), provider["only"])
        assertEquals(JsonPrimitive(false), provider["allow_fallbacks"])
        assertEquals(JsonPrimitive("deny"), provider["data_collection"])
        assertEquals("enabled", captured.headers?.get("X-OpenRouter-Metadata"))
        assertTrue(captured.headers?.get(HttpHeaders.Authorization).orEmpty().startsWith("Bearer "))
    }

    @Test
    fun `the returned ServedBy names the maker and the route and the reported cost`() = runTest {
        val result = client().callDetailed(request, routed)

        assertEquals("Hi there", result.completion.choices.single().message.content)
        assertEquals(
            ServedBy(
                providerId = "anthropic",
                modelId = AIModel_Claude.Sonnet_5.name,
                estimatedCostUsd = 0.00042,
                routingReason = "openrouter:anthropic",
                via = "openrouter",
            ),
            result.served,
        )
    }

    @Test
    fun `a host named by display name matches its slug`() = runTest {
        val geminiModel = AIProvider_OpenRouter.availableModels.first { it is AIModel_Gemini }
        val gemini = AIConfiguration_Default(AIProvider_OpenRouter, geminiModel)

        val result = client(body = response(host = "Google AI Studio")).callDetailed(request, gemini)

        assertEquals("openrouter:Google AI Studio", result.served?.routingReason)
    }

    @Test
    fun `call delegates to callDetailed`() = runTest {
        val completion = client().call(request, routed)

        assertEquals("gen-123", completion.id)
    }

    @Test
    fun `a configuration that is not routed through OpenRouter is refused before anything is sent`() = runTest {
        val captured = Captured()
        val direct = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

        assertFailsWith<IllegalArgumentException> { client(captured).callDetailed(request, direct) }
        assertNull(captured.body)
    }

    // Task 5: fail closed.

    @Test
    fun `a pinned call served by another host throws the mismatch`() = runTest {
        val failure = assertFailsWith<UpstreamHostMismatchException> {
            client(body = response(host = "Amazon Bedrock")).callDetailed(request, routed)
        }

        assertEquals(listOf("anthropic"), failure.expected)
        assertEquals("Amazon Bedrock", failure.actual)
    }

    @Test
    fun `a pinned call whose response names no host throws the mismatch`() = runTest {
        val failure = assertFailsWith<UpstreamHostMismatchException> {
            client(body = response(host = null)).callDetailed(request, routed)
        }

        assertNull(failure.actual)
    }

    @Test
    fun `an unpinned call accepts any host and reports none when none is named`() = runTest {
        val result = client(
            policy = OpenRouterRoutingPolicy(pinToMaker = false),
            body = response(host = null),
        ).callDetailed(request, routed)

        assertNull(result.served?.routingReason)
    }

    @Test
    fun `a non-success status throws with the status and a 200-character preview`() = runTest {
        val body = """{"error":{"code":503,"message":"No allowed providers are available"}}""" + "x".repeat(300)

        val failure = assertFailsWith<IllegalStateException> {
            client(status = HttpStatusCode.ServiceUnavailable, body = body).callDetailed(request, routed)
        }

        val message = failure.message.orEmpty()
        assertTrue(message.startsWith("OpenRouter answered 503: "))
        assertEquals(body.take(200), message.removePrefix("OpenRouter answered 503: "))
    }
}
