package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * An OAuth 2.0 token endpoint, as far as the refresh path needs one: the
 * `refresh_token` grant of RFC 6749 §6.
 *
 * Never throws for an answer the endpoint gave: the outcome is a
 * [OAuthTokenResult], because "the refresh token was refused" and "the
 * endpoint was down" call for opposite things from the operator.
 */
interface OAuthTokenEndpoint {

    /** Present [refreshToken] for [client] and report what the endpoint answered. */
    suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult
}

/** What a token endpoint answered. No variant's [toString] carries a token. */
sealed interface OAuthTokenResult {

    /**
     * New tokens (RFC 6749 §5.1).
     *
     * @property refreshToken The rotated refresh token, or null when the
     *   issuer kept the old one in force. An issuer that rotates has already
     *   spent the one it was handed, so this one must be persisted before the
     *   old one is let go.
     * @property expiresIn Seconds until [accessToken] expires, if stated.
     */
    data class Issued(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresIn: Long? = null,
    ) : OAuthTokenResult {
        override fun toString(): String =
            "Issued(accessToken=${redacted(accessToken)}, refreshToken=${redacted(refreshToken)}, " +
                "expiresIn=$expiresIn)"
    }

    /**
     * The endpoint refused the grant (RFC 6749 §5.2), e.g. `invalid_grant` for
     * a refresh token that is expired, revoked or already spent. Retrying the
     * same request cannot help; the credential has to be seeded again.
     *
     * @property error The RFC error code, reduced to `[A-Za-z0-9_.-]` so a
     *   misbehaving endpoint cannot smuggle anything else into a log line.
     */
    data class Rejected(val error: String) : OAuthTokenResult

    /** No usable answer: unreachable, a 5xx, a 429, or a body that is not a token response. Transient. */
    data class Unavailable(val reason: String) : OAuthTokenResult

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read a token endpoint's response body: a token response, or an error
         * response, whatever the HTTP status. Also what `ampere credential seed`
         * reads, since the operator pipes it the body of the code exchange.
         *
         * A body the endpoint answered with [status] that is neither is
         * [Unavailable]. Nothing from the body is quoted back except the error
         * code: a token response that fails to parse may still hold a token.
         */
        fun parse(body: String, status: Int = HttpStatusCode.OK.value): OAuthTokenResult {
            val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                return Unavailable("token endpoint answered $status with a body that is not a JSON object")
            }

            fields.string("error")?.let { error -> return Rejected(sanitize(error)) }

            if (status !in 200..299) {
                return Unavailable("token endpoint answered $status")
            }

            val accessToken = fields.string("access_token")
                ?: return Unavailable("token endpoint answered $status without an access_token")

            return Issued(
                accessToken = accessToken,
                refreshToken = fields.string("refresh_token"),
                expiresIn = fields.long("expires_in"),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

        // `expires_in` is a number in RFC 6749, and some issuers send it as a string anyway.
        private fun JsonObject.long(key: String): Long? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
        }

        private fun sanitize(error: String): String =
            error.filter { it.isLetterOrDigit() || it in "_.-" }.take(MAX_ERROR_LENGTH).ifEmpty { "unknown_error" }

        private const val MAX_ERROR_LENGTH = 64
    }
}

/**
 * The production [OAuthTokenEndpoint]: one form POST to the client's token
 * endpoint, client credentials in the body (RFC 6749 §2.3.1), and a public
 * client's secret simply left out.
 *
 * @param httpClient The client to POST with. The default discovers the
 *   platform engine; tests pass one over a mock engine.
 */
class KtorOAuthTokenEndpoint(
    private val httpClient: HttpClient = HttpClient(),
) : OAuthTokenEndpoint {

    override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult {
        val response = try {
            httpClient.submitForm(
                url = client.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", client.clientId)
                    client.clientSecret?.let { append("client_secret", it) }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The exception names the host at most; Ktor never puts the form body in one.
            return OAuthTokenResult.Unavailable("token endpoint unreachable: ${e::class.simpleName}: ${e.message}")
        }

        val status = response.status
        // 429 and 5xx are the endpoint's trouble, not the grant's, whatever the body says.
        if (status == HttpStatusCode.TooManyRequests || status.value >= 500) {
            return OAuthTokenResult.Unavailable("token endpoint answered ${status.value}")
        }

        val body = runCatching { response.bodyAsText() }.getOrElse {
            return OAuthTokenResult.Unavailable("token endpoint answered ${status.value} with no readable body")
        }

        return OAuthTokenResult.parse(body, status.value)
    }
}
