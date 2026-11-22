package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What a client asks a registration endpoint for (RFC 7591 §2): a public
 * client for the authorization-code flow, on one redirect URI.
 *
 * @property clientName What the authorization server's consent page calls the
 *   client. It is shown to the person, so it should be the host app's name.
 * @property redirectUri The one redirect URI the client will use.
 * @property grantTypes `authorization_code`, and `refresh_token` when the
 *   server offers it.
 * @property scopes The scopes the client intends to ask for, if it says.
 */
data class OAuthClientRegistration(
    val clientName: String,
    val redirectUri: String,
    val grantTypes: List<String> = listOf("authorization_code"),
    val scopes: Set<String> = emptySet(),
)

/** What a registration endpoint answered. [Registered] shows neither the client id nor a secret. */
sealed interface OAuthRegistrationResult {

    /**
     * A client was registered (RFC 7591 §3.2.1).
     *
     * @property clientSecret Null for a public client, which is what was asked for.
     */
    data class Registered(
        val clientId: String,
        val clientSecret: String? = null,
    ) : OAuthRegistrationResult {
        override fun toString(): String =
            "Registered(clientId=${redacted(clientId)}, clientSecret=${redacted(clientSecret)})"
    }

    /** The endpoint refused the registration (RFC 7591 §3.2.2). [error] is the code, reduced to safe characters. */
    data class Rejected(val error: String) : OAuthRegistrationResult

    /** No usable answer: unreachable, a 5xx, or a body that is not a registration response. */
    data class Unavailable(val reason: String) : OAuthRegistrationResult
}

/** Dynamic client registration (RFC 7591), as far as authorizing an MCP Link needs it. */
interface OAuthClientRegistrar {

    /** Register [registration] at [registrationEndpoint] and report what it answered. */
    suspend fun register(registrationEndpoint: String, registration: OAuthClientRegistration): OAuthRegistrationResult
}

/**
 * The production [OAuthClientRegistrar]: one JSON POST, asking for a public
 * client (`token_endpoint_auth_method: none`) that uses the `code` response
 * type.
 *
 * @param httpClient The client to POST with. The default discovers the
 *   platform engine; tests pass one over a mock engine.
 */
class KtorOAuthClientRegistrar(
    private val httpClient: HttpClient = HttpClient(),
) : OAuthClientRegistrar {

    override suspend fun register(
        registrationEndpoint: String,
        registration: OAuthClientRegistration,
    ): OAuthRegistrationResult {
        val request = buildJsonObject {
            put("client_name", registration.clientName)
            putJsonArray("redirect_uris") { add(registration.redirectUri) }
            putJsonArray("grant_types") { registration.grantTypes.forEach { add(it) } }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
            if (registration.scopes.isNotEmpty()) put("scope", registration.scopes.joinToString(" "))
        }

        val response = try {
            httpClient.post(registrationEndpoint) {
                header(HttpHeaders.Accept, "application/json")
                setBody(TextContent(request.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return OAuthRegistrationResult.Unavailable(
                "registration endpoint unreachable: ${e::class.simpleName}: ${e.message}",
            )
        }

        val status = response.status
        if (status == HttpStatusCode.TooManyRequests || status.value >= 500) {
            return OAuthRegistrationResult.Unavailable("registration endpoint answered ${status.value}")
        }

        // Nothing from the body is quoted back but the error code: it may hold a client secret.
        val fields = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrElse {
            return OAuthRegistrationResult.Unavailable(
                "registration endpoint answered ${status.value} with a body that is not a JSON object",
            )
        }

        fields.stringOrNull("error")?.let { return OAuthRegistrationResult.Rejected(sanitizeOAuthError(it)) }
        if (status.value !in 200..299) {
            return OAuthRegistrationResult.Unavailable("registration endpoint answered ${status.value}")
        }

        val clientId = fields.stringOrNull("client_id")
            ?: return OAuthRegistrationResult.Unavailable(
                "registration endpoint answered ${status.value} without a client_id",
            )
        return OAuthRegistrationResult.Registered(clientId, fields.stringOrNull("client_secret"))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
