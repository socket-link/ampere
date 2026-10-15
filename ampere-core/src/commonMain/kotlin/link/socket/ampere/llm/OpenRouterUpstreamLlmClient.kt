package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter

/**
 * A chat transport that reaches a model through OpenRouter, pinned by [policy]
 * to the model's maker, and reports who served the call and what it cost
 * (AMPR-437).
 *
 * The bundled transport cannot do this: the OpenAI-compatible request type has
 * no place for OpenRouter's `provider` object and its response type keeps
 * neither the serving host nor the reported cost. This class is one Ktor POST
 * that adds both, and nothing else changes — the request is serialized with the
 * request type's own serializer.
 *
 * Each call:
 * - carries the configuration's wire id and [policy]'s `provider` object, and
 *   asks for routing metadata (`X-OpenRouter-Metadata: enabled`) so the
 *   response names the host that served it;
 * - returns a [ServedBy] naming the maker in `providerId`, `openrouter` in
 *   `via`, the reported cost, and `openrouter:<host>` as the routing reason;
 * - when [OpenRouterRoutingPolicy.pinToMaker] is set, fails closed with
 *   [UpstreamHostMismatchException] if the response names another host or
 *   none. The prompt has already left by then; the caller must learn that.
 *
 * ## Opted into, never inherited
 *
 * Nothing wires this class in (AMPR-236). It carries routed configurations only
 * — a configuration whose provider is not OpenRouter is an
 * [IllegalArgumentException] — and it does not fall back by itself: a refused
 * or failed call throws.
 *
 * ## Composing a fallback
 *
 * Ordering this transport ahead of a direct one is the caller's to write, so
 * the caller decides which failures warrant sending the prompt to a second
 * company. A host mismatch is not one of them — that call was already served:
 *
 * ```kotlin
 * class GatewayFirst(
 *     private val gateway: OpenRouterUpstreamLlmClient,
 *     private val direct: UpstreamLlmClient,
 *     private val directFor: (AIConfiguration) -> AIConfiguration,
 * ) : UpstreamLlmClient {
 *     override suspend fun call(request: ChatCompletionRequest, configuration: AIConfiguration) =
 *         callDetailed(request, configuration).completion
 *
 *     override suspend fun callDetailed(request: ChatCompletionRequest, configuration: AIConfiguration) =
 *         try {
 *             gateway.callDetailed(request, configuration)
 *         } catch (mismatch: UpstreamHostMismatchException) {
 *             throw mismatch
 *         } catch (failure: Exception) {
 *             val fallback = directFor(configuration)
 *             direct.callDetailed(request.copy(model = ModelId(fallback.wireModelId)), fallback)
 *         }
 * }
 * ```
 *
 * Non-streaming, like the seam it implements.
 *
 * @param policy Which hosts may serve the call.
 * @param httpClient The client to POST with. Tests pass one over a mock engine.
 */
@AmpereStableApi
class OpenRouterUpstreamLlmClient(
    private val policy: OpenRouterRoutingPolicy = OpenRouterRoutingPolicy(),
    private val httpClient: HttpClient = HttpClient(),
) : UpstreamLlmClient {

    /**
     * [policy] over the platform's discovered engine. A separate constructor
     * rather than only a default argument, because Swift sees no defaults and
     * cannot build a Ktor `HttpClient`.
     */
    constructor(policy: OpenRouterRoutingPolicy) : this(policy, HttpClient())

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion = callDetailed(request, configuration).completion

    override suspend fun callDetailed(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): UpstreamCompletion {
        // The route first: a configuration with backups decides which of its
        // configurations answers when its provider is read.
        val provider = configuration.provider
        require(provider.id == AIProvider_OpenRouter.id) {
            "OpenRouterUpstreamLlmClient carries routed configurations only; got provider '${provider.id}'"
        }
        val model = configuration.model
        val makerId = model.makerId
        val providerObject = policy.providerObject(makerId)

        val requestObject = OpenRouterJson.encodeToJsonElement(ChatCompletionRequest.serializer(), request).jsonObject
        val body = JsonObject(
            requestObject + mapOf(
                "model" to JsonPrimitive(configuration.wireModelId),
                "provider" to providerObject,
            ),
        )

        val response = httpClient.post(CHAT_COMPLETIONS_URL) {
            header(HttpHeaders.Authorization, "Bearer ${provider.apiToken}")
            header(METADATA_HEADER, "enabled")
            contentType(ContentType.Application.Json)
            setBody(OpenRouterJson.encodeToString(JsonObject.serializer(), body))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "OpenRouter answered ${response.status.value}: ${text.take(ERROR_BODY_PREVIEW)}",
            )
        }

        val envelope = OpenRouterJson.parseToJsonElement(text).jsonObject
        val host = servingHost(envelope)
        if (policy.pinToMaker) {
            val expected = OpenRouterRoutingPolicy.hostsFor(makerId)
            if (host == null || OpenRouterRoutingPolicy.normalizeHost(host) !in expected) {
                throw UpstreamHostMismatchException(expected = expected, actual = host)
            }
        }

        val completion = OpenRouterJson.decodeFromJsonElement(ChatCompletion.serializer(), envelope)
        return UpstreamCompletion(
            completion = completion,
            served = ServedBy(
                providerId = makerId,
                modelId = model.name,
                estimatedCostUsd = reportedCost(envelope),
                routingReason = host?.let { "$VIA:$it" },
                via = VIA,
            ),
        )
    }

    private companion object {
        const val VIA = "openrouter"
        const val CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val METADATA_HEADER = "X-OpenRouter-Metadata"
        const val ERROR_BODY_PREVIEW = 200

        val OpenRouterJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * The host OpenRouter says served the call: the last successful entry
         * in `openrouter_metadata.attempts`, else the selected endpoint in
         * `openrouter_metadata.endpoints.available`. Null when neither is there.
         */
        fun servingHost(envelope: JsonObject): String? {
            val metadata = envelope["openrouter_metadata"] as? JsonObject ?: return null
            val attempted = (metadata["attempts"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.lastOrNull { attempt -> attempt.int("status") in 200..299 }
                ?.string("provider")
            if (attempted != null) return attempted
            return ((metadata["endpoints"] as? JsonObject)?.get("available") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { endpoint -> (endpoint["selected"] as? JsonPrimitive)?.contentOrNull == "true" }
                ?.string("provider")
        }

        /** `usage.cost`, which OpenRouter reports in credits priced one-to-one with US dollars. */
        fun reportedCost(envelope: JsonObject): Double? =
            ((envelope["usage"] as? JsonObject)?.get("cost") as? JsonPrimitive)?.doubleOrNull

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}

/**
 * OpenRouter served a pinned call from a host other than the maker's own, or
 * did not say which host served it (AMPR-437).
 *
 * Thrown after the response arrived, so the prompt has already reached
 * [actual]. Nothing from the response is returned: the caller learns the
 * promise about who receives the prompt was not kept, and must not treat the
 * call as served.
 *
 * @property expected The maker's own hosts, as OpenRouter slugs.
 * @property actual The host the response named, or null when it named none.
 */
@AmpereStableApi
class UpstreamHostMismatchException(
    val expected: List<String>,
    val actual: String?,
) : IllegalStateException(
    "OpenRouter was pinned to ${expected.joinToString()} but the response named " +
        (actual?.let { "'$it'" } ?: "no serving host"),
)
