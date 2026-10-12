package link.socket.ampere.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * Which hosts OpenRouter may hand a call to (AMPR-437).
 *
 * OpenRouter can serve one model from several hosting companies, not only the
 * model's maker. A consumer that has promised a person who receives their prompt
 * needs that choice made here, per request, rather than left to the gateway.
 * The defaults are that promise: the maker's own hosting, no fallback to anyone
 * else, and no host that may store or train on the data.
 *
 * Rendered as the chat request's `provider` object by [OpenRouterUpstreamLlmClient].
 *
 * @property pinToMaker Send `order` and `only` naming the maker's own hosting.
 *   A maker with no known OpenRouter host is then an [IllegalArgumentException]
 *   at call time, before anything is sent.
 * @property allowFallbacks Sent as `allow_fallbacks`. False makes OpenRouter
 *   return the upstream error instead of trying another host.
 * @property denyDataCollection Sent as `data_collection`: `deny` excludes hosts
 *   that may store user data non-transiently or train on it.
 */
@AmpereStableApi
data class OpenRouterRoutingPolicy(
    val pinToMaker: Boolean = true,
    val allowFallbacks: Boolean = false,
    val denyDataCollection: Boolean = true,
) {

    /** The `provider` object for a call to a model made by [makerId]. */
    internal fun providerObject(makerId: ProviderId): JsonObject = buildJsonObject {
        if (pinToMaker) {
            val hosts = hostsFor(makerId)
            putJsonArray("order") { hosts.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("only") { hosts.forEach { add(JsonPrimitive(it)) } }
        }
        put("allow_fallbacks", allowFallbacks)
        put("data_collection", if (denyDataCollection) "deny" else "allow")
    }

    internal companion object {

        /**
         * OpenRouter's host slugs for each maker's own hosting, in preference
         * order. Google hosts Gemini on two of its own platforms; both are Google.
         */
        private val MAKER_HOSTS: Map<ProviderId, List<String>> = mapOf(
            "anthropic" to listOf("anthropic"),
            "openai" to listOf("openai"),
            "google" to listOf("google-ai-studio", "google-vertex"),
        )

        /** The maker's hosts; throws naming [makerId] when OpenRouter has none on record here. */
        fun hostsFor(makerId: ProviderId): List<String> =
            requireNotNull(MAKER_HOSTS[makerId]) {
                "No OpenRouter host is known for maker '$makerId', so a call cannot be pinned to it"
            }

        /**
         * A host as OpenRouter names it, in slug form: the response's metadata may
         * use the display name (`Google AI Studio`) where the request used the
         * slug (`google-ai-studio`).
         */
        fun normalizeHost(host: String): String = host.trim().lowercase().replace(' ', '-')
    }
}
