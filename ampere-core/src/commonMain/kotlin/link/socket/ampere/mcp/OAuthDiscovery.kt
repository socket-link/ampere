package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.mcp.McpAuthorizationException.DiscoveryFailed
import link.socket.ampere.mcp.McpAuthorizationException.DiscoveryFailed.Missing
import link.socket.ampere.mcp.McpAuthorizationException.MetadataRefused

/**
 * Where an MCP server sends a client to be authorized, as discovery found it
 * (AMPR-516): the protected resource's metadata (RFC 9728) joined with its
 * authorization server's (RFC 8414), checked, and reduced to what the
 * authorization-code flow reads.
 *
 * Nothing here is a secret. Every URL has been checked to be `https`, or
 * `http` on a loopback host.
 *
 * @property mcpUri The MCP endpoint discovery started from.
 * @property resource The resource indicator to send (RFC 8707): the
 *   `resource` the server's metadata states, else [mcpUri] without a fragment.
 * @property issuer The authorization server's issuer identifier, which a
 *   redirect's `iss` is compared with (RFC 9207).
 * @property registrationEndpoint Where to register a client (RFC 7591), or
 *   null when the server offers no dynamic registration.
 * @property revocationEndpoint Where to revoke a token (RFC 7009), or null
 *   when the server advertises none.
 * @property scopesSupported The scopes the protected resource names, else the
 *   authorization server's. Empty when neither names any.
 * @property challengedScopes The `scope` of the 401's `WWW-Authenticate`
 *   challenge, when it stated one: what this request needs, by the server's
 *   own account.
 * @property issParameterSupported Whether the server puts `iss` on every
 *   authorization response, so that a response without one is refused.
 * @property refreshTokenGrantSupported Whether the server lists the
 *   `refresh_token` grant. When false, expect a credential with no refresh
 *   token.
 */
data class McpAuthorizationServer(
    val mcpUri: String,
    val resource: String,
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    val revocationEndpoint: String? = null,
    val scopesSupported: Set<String> = emptySet(),
    val challengedScopes: Set<String>? = null,
    val issParameterSupported: Boolean = false,
    val refreshTokenGrantSupported: Boolean = false,
)

/**
 * Finds an MCP server's authorization server (AMPR-516).
 *
 * A failure is always a [McpAuthorizationException]: [DiscoveryFailed] naming
 * the piece that was missing, or [MetadataRefused] for metadata that must not
 * be acted on.
 */
interface OAuthDiscovery {

    /**
     * @param mcpUri The MCP endpoint.
     * @param wwwAuthenticate The `WWW-Authenticate` value of a 401 the caller
     *   already received from [mcpUri], if it has one. Without it, discovery
     *   asks the endpoint itself.
     */
    suspend fun discover(mcpUri: String, wwwAuthenticate: String? = null): Result<McpAuthorizationServer>
}

/**
 * The production [OAuthDiscovery], over Ktor.
 *
 * 1. **The 401.** With no [wwwAuthenticate][discover] handed in, one
 *    unauthenticated JSON-RPC `ping` is POSTed to the endpoint with
 *    `Accept: application/json, text/event-stream`. A spec-current server
 *    refuses a bare `GET` with a 405 and a `POST` without that header with a
 *    406, so only this request reaches the 401 that names the metadata. A
 *    `ping` opens no session on a server that turns out to need no token.
 * 2. **Protected-resource metadata** (RFC 9728): the challenge's
 *    `resource_metadata` URL, then the well-known path for the endpoint's own
 *    path, then the one at the root. The first that answers is used. Its
 *    `resource` must cover the MCP endpoint (same origin, same path or a
 *    parent of it), or a server could hand a client a token meant for another.
 * 3. **Authorization-server metadata** (RFC 8414), for each server the
 *    resource names, in order: the RFC 8414 well-known path, then the two
 *    OpenID Connect ones. The `issuer` it states must be the one asked for,
 *    so an issuer with a path is never answered by the document at the root.
 * 4. **What the flow needs.** An authorization endpoint, a token endpoint, the
 *    `authorization_code` grant and `S256`. A server that does not advertise
 *    `S256` is refused rather than tried: PKCE is the only thing protecting a
 *    public client's code.
 *
 * Every URL fetched or returned is `https`, or `http` on a loopback host.
 *
 * @param httpClient The client to ask with. The default discovers the platform
 *   engine; tests pass one over a mock engine.
 */
class KtorOAuthDiscovery(
    private val httpClient: HttpClient = HttpClient(),
) : OAuthDiscovery {

    override suspend fun discover(mcpUri: String, wwwAuthenticate: String?): Result<McpAuthorizationServer> =
        try {
            Result.success(discoverOrThrow(mcpUri, wwwAuthenticate))
        } catch (e: McpAuthorizationException) {
            Result.failure(e)
        }

    private suspend fun discoverOrThrow(mcpUri: String, wwwAuthenticate: String?): McpAuthorizationServer {
        val endpoint = parseOAuthUrl(mcpUri)
            ?: throw DiscoveryFailed(mcpUri, Missing.MCP_ENDPOINT, "The URI is not an http(s) URL.")

        val challenge = wwwAuthenticate?.let(BearerChallenge::parse) ?: probe(mcpUri)

        val (resourceMetadataUrl, resourceMetadata) = protectedResourceMetadata(mcpUri, endpoint, challenge)
        val resource = resourceIndicator(mcpUri, endpoint, resourceMetadata, resourceMetadataUrl)

        if (resourceMetadata.authorizationServers.isEmpty()) {
            throw DiscoveryFailed(
                mcpUri,
                Missing.AUTHORIZATION_SERVER,
                "$resourceMetadataUrl lists no authorization_servers.",
            )
        }

        val server = authorizationServerMetadata(mcpUri, resourceMetadata.authorizationServers)

        val authorizationEndpoint = server.authorizationEndpoint
            ?: throw DiscoveryFailed(mcpUri, Missing.AUTHORIZATION_ENDPOINT, "Issuer ${server.issuer} states none.")
        val tokenEndpoint = server.tokenEndpoint
            ?: throw DiscoveryFailed(mcpUri, Missing.TOKEN_ENDPOINT, "Issuer ${server.issuer} states none.")

        // RFC 8414 §2: an absent grant_types_supported means ["authorization_code", "implicit"].
        val grantTypes = server.grantTypesSupported ?: listOf(AUTHORIZATION_CODE, "implicit")
        if (AUTHORIZATION_CODE !in grantTypes) {
            throw DiscoveryFailed(
                mcpUri,
                Missing.AUTHORIZATION_CODE_GRANT,
                "Issuer ${server.issuer} offers ${grantTypes.joinToString()} only.",
            )
        }
        if (PkceChallenge.METHOD !in server.codeChallengeMethodsSupported) {
            throw DiscoveryFailed(
                mcpUri,
                Missing.PKCE_S256,
                "Issuer ${server.issuer} advertises " +
                    server.codeChallengeMethodsSupported.ifEmpty { listOf("no code challenge method") }.joinToString() +
                    ".",
            )
        }

        listOfNotNull(authorizationEndpoint, tokenEndpoint, server.registrationEndpoint, server.revocationEndpoint)
            .forEach { requireSecure(mcpUri, it) }

        return McpAuthorizationServer(
            mcpUri = mcpUri,
            resource = resource,
            issuer = server.issuer,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = server.registrationEndpoint,
            revocationEndpoint = server.revocationEndpoint,
            scopesSupported = resourceMetadata.scopesSupported.ifEmpty { server.scopesSupported }.toSet(),
            challengedScopes = challenge?.scopes,
            issParameterSupported = server.authorizationResponseIssParameterSupported,
            refreshTokenGrantSupported = "refresh_token" in grantTypes,
        )
    }

    /** One unauthenticated request to the MCP endpoint, for the 401's challenge. Null when it sent none. */
    private suspend fun probe(mcpUri: String): BearerChallenge? {
        val response = try {
            httpClient.post(mcpUri) {
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                setBody(TextContent(PROBE_BODY, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DiscoveryFailed(
                mcpUri,
                Missing.MCP_ENDPOINT,
                "It did not answer (${e::class.simpleName}: ${e.message}).",
            )
        }
        if (response.status != HttpStatusCode.Unauthorized) return null

        return response.headers.getAll(HttpHeaders.WWWAuthenticate)
            ?.joinToString(", ")
            ?.let(BearerChallenge::parse)
    }

    private suspend fun protectedResourceMetadata(
        mcpUri: String,
        endpoint: Url,
        challenge: BearerChallenge?,
    ): Pair<String, OAuthProtectedResourceMetadata> {
        val candidates = (
            listOfNotNull(challenge?.resourceMetadata) +
                wellKnown(endpoint, PROTECTED_RESOURCE, insertBeforePath = true, rootFallback = true)
            ).distinct()

        for (url in candidates) {
            requireSecure(mcpUri, url)
            val metadata = fetch(url, OAuthProtectedResourceMetadata.serializer()) ?: continue
            return url to metadata
        }

        throw DiscoveryFailed(
            mcpUri,
            Missing.PROTECTED_RESOURCE_METADATA,
            (if (challenge?.resourceMetadata == null) "The endpoint's 401 named no resource_metadata, and " else "") +
                "none of ${candidates.joinToString()} answered with it.",
        )
    }

    /**
     * The resource indicator to send. RFC 9728 §3.3: metadata whose `resource`
     * is not the resource that was asked about must not be used.
     */
    private fun resourceIndicator(
        mcpUri: String,
        endpoint: Url,
        metadata: OAuthProtectedResourceMetadata,
        metadataUrl: String,
    ): String {
        val stated = metadata.resource ?: return mcpUri.substringBefore('#')
        val resource = parseOAuthUrl(stated)
        val covers = resource != null &&
            resource.originText() == endpoint.originText() &&
            endpoint.encodedPath.isUnder(resource.encodedPath)
        if (!covers) {
            throw MetadataRefused(
                mcpUri,
                MetadataRefused.Reason.RESOURCE_MISMATCH,
                "$metadataUrl describes the resource $stated, which is not this endpoint.",
            )
        }
        return stated
    }

    private suspend fun authorizationServerMetadata(
        mcpUri: String,
        issuers: List<String>,
    ): OAuthAuthorizationServerMetadata {
        val tried = mutableListOf<String>()
        for (issuer in issuers) {
            requireSecure(mcpUri, issuer)
            val issuerUrl = parseOAuthUrl(issuer) ?: continue
            val candidates = wellKnown(issuerUrl, AUTHORIZATION_SERVER, insertBeforePath = true) +
                wellKnown(issuerUrl, OPENID_CONFIGURATION, insertBeforePath = true) +
                wellKnown(issuerUrl, OPENID_CONFIGURATION, insertBeforePath = false)

            for (url in candidates.distinct()) {
                tried += url
                val metadata = fetch(url, OAuthAuthorizationServerMetadata.serializer()) ?: continue
                if (metadata.issuer.trimEnd('/') != issuer.trimEnd('/')) {
                    throw MetadataRefused(
                        mcpUri,
                        MetadataRefused.Reason.ISSUER_MISMATCH,
                        "$url states the issuer ${metadata.issuer}, and the resource named $issuer.",
                    )
                }
                return metadata
            }
        }
        throw DiscoveryFailed(
            mcpUri,
            Missing.AUTHORIZATION_SERVER_METADATA,
            "None of ${tried.joinToString()} answered with it.",
        )
    }

    /** The JSON document at [url], or null when it is not there or is not one. Metadata holds no secret. */
    private suspend fun <T> fetch(url: String, serializer: KSerializer<T>): T? {
        val body = try {
            val response = httpClient.get(url) { header(HttpHeaders.Accept, "application/json") }
            if (response.status != HttpStatusCode.OK) return null
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return runCatching { json.decodeFromString(serializer, body) }.getOrNull()
    }

    private fun requireSecure(mcpUri: String, url: String) {
        val parsed = parseOAuthUrl(url)
        val secure = parsed != null && (parsed.protocol.name == "https" || parsed.host.isLoopback())
        if (!secure) {
            throw MetadataRefused(
                mcpUri,
                MetadataRefused.Reason.INSECURE_URL,
                "$url is neither https nor http on a loopback host.",
            )
        }
    }

    private companion object {

        val json = Json { ignoreUnknownKeys = true }

        const val AUTHORIZATION_CODE = "authorization_code"
        const val PROTECTED_RESOURCE = "oauth-protected-resource"
        const val AUTHORIZATION_SERVER = "oauth-authorization-server"
        const val OPENID_CONFIGURATION = "openid-configuration"

        /** A request an MCP server answers without a session, so a server that needs no token is left as it was. */
        const val PROBE_BODY = """{"jsonrpc":"2.0","id":"ampere-authorization-probe","method":"ping"}"""
    }
}

/** RFC 9728 §2, as far as discovery reads it. */
@Serializable
internal data class OAuthProtectedResourceMetadata(
    val resource: String? = null,
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
)

/** RFC 8414 §2 and RFC 9207 §3, as far as discovery reads them. */
@Serializable
internal data class OAuthAuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String>? = null,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = emptyList(),
    @SerialName("authorization_response_iss_parameter_supported")
    val authorizationResponseIssParameterSupported: Boolean = false,
)

/**
 * The parameters of a `WWW-Authenticate` challenge that discovery reads:
 * `resource_metadata` (RFC 9728 §5.1) and `scope` (RFC 6750 §3).
 */
internal class BearerChallenge(
    val resourceMetadata: String?,
    val scopes: Set<String>?,
) {
    companion object {

        // name = "quoted \" string" | name = token
        private val parameter = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^\s,"]+))""")
        private val escape = Regex("""\\(.)""")

        /** Null when [header] carries neither parameter. */
        fun parse(header: String): BearerChallenge? {
            val parameters = parameter.findAll(header).associate { match ->
                val quoted = match.groups[2]?.value?.let { escape.replace(it) { found -> found.groupValues[1] } }
                match.groupValues[1].lowercase() to (quoted ?: match.groups[3]?.value.orEmpty())
            }
            val resourceMetadata = parameters["resource_metadata"]?.takeIf { it.isNotEmpty() }
            val scopes = parameters["scope"]?.let(::parseScopes)?.takeIf { it.isNotEmpty() }
            if (resourceMetadata == null && scopes == null) return null
            return BearerChallenge(resourceMetadata, scopes)
        }
    }
}

/** [text] as an `http(s)` URL with a host, or null. */
internal fun parseOAuthUrl(text: String): Url? {
    if (!text.startsWith("https://", ignoreCase = true) && !text.startsWith("http://", ignoreCase = true)) return null
    return runCatching { Url(text) }.getOrNull()?.takeIf { it.host.isNotEmpty() }
}

/** Whether a host is this machine: the only place `http` carries an OAuth request (RFC 8252 §8.3). */
internal fun String.isLoopback(): Boolean {
    val host = trim('[', ']').lowercase()
    return host == "localhost" || host == "::1" || loopbackIpv4.matches(host)
}

// A literal only: `127.example.com` is somebody else's machine.
private val loopbackIpv4 = Regex("""127(\.\d{1,3}){3}""")

/** `scheme://host[:port]`, the port left out when it is the scheme's default. */
private fun Url.originText(): String {
    val shownPort = if (port == protocol.defaultPort) "" else ":$port"
    return "${protocol.name}://${host.lowercase()}$shownPort"
}

/** Whether this path is [parent] or lies beneath it, a trailing slash aside. */
private fun String.isUnder(parent: String): Boolean {
    val base = parent.trimEnd('/')
    val path = trimEnd('/')
    return path == base || path.startsWith("$base/")
}

/**
 * The well-known URLs for [suffix] at [base], most specific first.
 *
 * With [insertBeforePath], RFC 8414 §3.1 and RFC 9728 §3.1: the well-known
 * segment goes between the host and the path. Without it, OpenID Connect
 * Discovery's form: the segment is appended to the path.
 *
 * [rootFallback] adds the form with no path after the one with it. That is
 * right for a protected resource, whose metadata may describe a parent of the
 * endpoint, and wrong for an issuer: the document at the root belongs to a
 * different issuer, and would be refused as one.
 */
private fun wellKnown(
    base: Url,
    suffix: String,
    insertBeforePath: Boolean,
    rootFallback: Boolean = false,
): List<String> {
    val origin = base.originText()
    val path = base.encodedPath.trimEnd('/')
    return when {
        !insertBeforePath -> listOf("$origin$path/.well-known/$suffix")
        path.isEmpty() -> listOf("$origin/.well-known/$suffix")
        else -> listOfNotNull(
            "$origin/.well-known/$suffix$path",
            "$origin/.well-known/$suffix".takeIf { rootFallback },
        )
    }
}
