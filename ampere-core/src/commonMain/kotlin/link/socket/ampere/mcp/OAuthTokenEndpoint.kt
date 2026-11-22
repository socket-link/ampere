package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * An OAuth 2.0 token endpoint, as far as a Link's credential needs one: the
 * `refresh_token` grant of RFC 6749 §6 (AMPR-464), and since AMPR-516 the
 * `authorization_code` grant of §4.1.3 with PKCE (RFC 7636) and the
 * revocation of a token (RFC 7009).
 *
 * Never throws for an answer the endpoint gave: the outcome is a
 * [OAuthTokenResult], because "the refresh token was refused" and "the
 * endpoint was down" call for opposite things from the operator.
 *
 * [exchangeCode] and [revoke] have defaults so an implementation written when
 * this was the refresh grant alone still compiles; each default reports that
 * the call is not supported rather than pretending it succeeded.
 */
interface OAuthTokenEndpoint {

    /** Present [refreshToken] for [client] and report what the endpoint answered. */
    suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult

    /**
     * Redeem an authorization code for [client] and report what the endpoint
     * answered. An authorization code is single-use: whatever this returns,
     * the code in [exchange] is spent.
     */
    suspend fun exchangeCode(client: McpOAuthClient, exchange: OAuthCodeExchange): OAuthTokenResult =
        OAuthTokenResult.Unavailable("this token endpoint client does not implement the authorization_code grant")

    /**
     * Ask [McpOAuthClient.revocationEndpoint] to revoke [token] (RFC 7009).
     *
     * @param tokenTypeHint `refresh_token` or `access_token`, RFC 7009 §2.1.
     */
    suspend fun revoke(client: McpOAuthClient, token: String, tokenTypeHint: String): OAuthRevocationResult =
        OAuthRevocationResult.Failed("this token endpoint client does not implement revocation")
}

/**
 * What the `authorization_code` grant presents beside the client: the code the
 * redirect carried, the PKCE verifier whose challenge the authorization
 * request sent, the redirect URI that request named, and the resource the
 * token is for (RFC 8707).
 *
 * [code] and [codeVerifier] are credentials for as long as the exchange takes,
 * so [toString] shows neither.
 */
class OAuthCodeExchange(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val resource: String,
) {
    override fun toString(): String =
        "OAuthCodeExchange(code=${redacted(code)}, codeVerifier=${redacted(codeVerifier)}, " +
            "redirectUri=$redirectUri, resource=$resource)"
}

/** What a token endpoint answered. No variant's [toString] carries a token. */
sealed interface OAuthTokenResult {

    /**
     * New tokens (RFC 6749 §5.1).
     *
     * @property refreshToken The rotated refresh token, or null when the
     *   issuer kept the old one in force — or, for a code exchange, issued
     *   none at all. An issuer that rotates has already spent the one it was
     *   handed, so this one must be persisted before the old one is let go.
     * @property expiresIn Seconds until [accessToken] expires, if stated.
     * @property scopes The scopes the tokens carry, when the endpoint stated
     *   them. It may state more than was asked for, or fewer; null means it
     *   said nothing, which RFC 6749 §5.1 allows only when the grant is
     *   exactly what was requested.
     */
    data class Issued(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresIn: Long? = null,
        val scopes: Set<String>? = null,
    ) : OAuthTokenResult {
        override fun toString(): String =
            "Issued(accessToken=${redacted(accessToken)}, refreshToken=${redacted(refreshToken)}, " +
                "expiresIn=$expiresIn, scopes=$scopes)"
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

            fields.stringOrNull("error")?.let { error -> return Rejected(sanitizeOAuthError(error)) }

            if (status !in 200..299) {
                return Unavailable("token endpoint answered $status")
            }

            val accessToken = fields.stringOrNull("access_token")
                ?: return Unavailable("token endpoint answered $status without an access_token")

            return Issued(
                accessToken = accessToken,
                refreshToken = fields.stringOrNull("refresh_token"),
                expiresIn = fields.long("expires_in"),
                scopes = fields.scopes("scope"),
            )
        }

        // `expires_in` is a number in RFC 6749, and some issuers send it as a string anyway.
        private fun JsonObject.long(key: String): Long? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
        }

        // `scope` is one space-delimited string in RFC 6749 §3.3. Issuers also send it
        // comma-delimited, or as a JSON array; all three are read, and an empty one is none.
        private fun JsonObject.scopes(key: String): Set<String>? {
            val stated = when (val value = this[key]) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                is JsonPrimitive -> if (value.isString) listOf(value.content) else return null
                else -> return null
            }
            return parseScopes(stated.joinToString(" ")).takeIf { it.isNotEmpty() }
        }
    }
}

/** What a revocation endpoint answered (RFC 7009 §2.2). */
sealed interface OAuthRevocationResult {

    /** A 200: the token is revoked, or was never valid, which RFC 7009 answers the same way. */
    data object Revoked : OAuthRevocationResult

    /** Anything else: no endpoint to call, unreachable, or an error answer. [reason] quotes no token. */
    data class Failed(val reason: String) : OAuthRevocationResult
}

/** A non-empty JSON string under [key], or null. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

/** An OAuth `error` code reduced to `[A-Za-z0-9_.-]`, so nothing else from a response reaches a message. */
internal fun sanitizeOAuthError(error: String): String =
    error.filter { it.isLetterOrDigit() || it in "_.-" }.take(MAX_OAUTH_ERROR_LENGTH).ifEmpty { "unknown_error" }

/** Scope tokens out of a `scope` value: space-delimited by RFC 6749 §3.3, comma-delimited by some issuers. */
internal fun parseScopes(scope: String): Set<String> =
    scope.split(' ', ',', '\t', '\n', '\r').filterTo(LinkedHashSet()) { it.isNotEmpty() }

private const val MAX_OAUTH_ERROR_LENGTH = 64

/**
 * The production [OAuthTokenEndpoint]: one form POST per call, client
 * credentials in the body (RFC 6749 §2.3.1), and a public client's secret
 * simply left out.
 *
 * @param httpClient The client to POST with. The default discovers the
 *   platform engine; tests pass one over a mock engine.
 */
class KtorOAuthTokenEndpoint(
    private val httpClient: HttpClient = HttpClient(),
) : OAuthTokenEndpoint {

    override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult =
        token(
            client.tokenEndpoint,
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                appendClient(client)
            },
        )

    override suspend fun exchangeCode(client: McpOAuthClient, exchange: OAuthCodeExchange): OAuthTokenResult =
        token(
            client.tokenEndpoint,
            parameters {
                append("grant_type", "authorization_code")
                append("code", exchange.code)
                append("redirect_uri", exchange.redirectUri)
                append("code_verifier", exchange.codeVerifier)
                append("resource", exchange.resource)
                appendClient(client)
            },
        )

    override suspend fun revoke(client: McpOAuthClient, token: String, tokenTypeHint: String): OAuthRevocationResult {
        val endpoint = client.revocationEndpoint
            ?: return OAuthRevocationResult.Failed("the authorization server advertises no revocation endpoint")

        val response = post(
            endpoint,
            parameters {
                append("token", token)
                append("token_type_hint", tokenTypeHint)
                appendClient(client)
            },
        ).getOrElse { return OAuthRevocationResult.Failed("revocation endpoint unreachable: ${it.describe()}") }

        // RFC 7009 §2.2: a 200 whether or not the token was valid. The body carries nothing to read.
        return if (response.status == HttpStatusCode.OK) {
            OAuthRevocationResult.Revoked
        } else {
            OAuthRevocationResult.Failed("revocation endpoint answered ${response.status.value}")
        }
    }

    private suspend fun token(endpoint: String, form: Parameters): OAuthTokenResult {
        val response = post(endpoint, form).getOrElse {
            return OAuthTokenResult.Unavailable("token endpoint unreachable: ${it.describe()}")
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

    private suspend fun post(endpoint: String, form: Parameters): Result<HttpResponse> =
        try {
            Result.success(httpClient.submitForm(url = endpoint, formParameters = form))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun ParametersBuilder.appendClient(client: McpOAuthClient) {
        append("client_id", client.clientId)
        client.clientSecret?.let { append("client_secret", it) }
    }

    // The exception names the host at most; Ktor never puts the form body in one.
    private fun Throwable.describe(): String = "${this::class.simpleName}: $message"
}
