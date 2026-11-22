package link.socket.ampere.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A real HTTP MCP endpoint with its authorization server behind it, on a
 * loopback port, for the flow end to end (AMPR-516).
 *
 * It behaves like the server the ticket was written against, described here by
 * behaviour only: a bare `GET` on the MCP endpoint is a 405, a `POST` without
 * `Accept: application/json, text/event-stream` is a 406, an unauthenticated
 * `POST` is a 401 naming the protected-resource metadata, the authorization
 * server is a public-client, `S256`-only, `authorization_code`-only issuer
 * with a registration endpoint and `iss` on its redirects, and the token
 * response carries no refresh token, thirty days, and one scope more than was
 * asked for. Each of those can be turned off or changed.
 *
 * Every id, code and token it mints is an obvious fake. It checks PKCE with
 * the JDK's own SHA-256, not the code under test.
 */
internal class FixtureAuthorizationServer(
    var offerRegistration: Boolean = true,
    var issueRefreshToken: Boolean = false,
    var widenScope: Boolean = true,
    var advertiseRevocation: Boolean = false,
    var challengeNamesMetadata: Boolean = true,
    var serveResourceMetadata: Boolean = true,
    var advertiseIss: Boolean = true,
) : AutoCloseable {

    private val http =
        HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)

    val origin: String = "http://127.0.0.1:${http.address.port}"
    val mcpUri: String = "$origin/mcp"
    val issuer: String = origin

    /** What the authorize page sends back instead of the request's own `state`, or its own `iss`. */
    var redirectState: String? = null
    var redirectIssuer: String? = null

    val registrations = CopyOnWriteArrayList<JsonObject>()
    val authorizeRequests = CopyOnWriteArrayList<Map<String, String>>()
    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    val revocationRequests = CopyOnWriteArrayList<Map<String, String>>()
    val fetched = CopyOnWriteArrayList<String>()

    private val clients = CopyOnWriteArrayList(listOf(PREREGISTERED_CLIENT))
    private val codes = mutableMapOf<String, Map<String, String>>()
    private val liveTokens = CopyOnWriteArrayList<String>()

    init {
        http.createContext("/") { exchange ->
            exchange.use {
                fetched += "${exchange.requestMethod} ${exchange.requestURI.path}"
                when (exchange.requestURI.path) {
                    "/mcp" -> mcp(exchange)
                    "/.well-known/oauth-protected-resource/mcp" -> resourceMetadata(exchange)
                    "/.well-known/oauth-authorization-server" -> serverMetadata(exchange)
                    "/register" -> register(exchange)
                    "/authorize" -> authorize(exchange)
                    "/token" -> token(exchange)
                    "/revoke" -> revoke(exchange)
                    else -> exchange.reply(404, "{}")
                }
            }
        }
        http.start()
    }

    /** Whether the MCP endpoint accepts [token] right now, asked the way a client would. */
    fun accepts(token: String?): Boolean {
        val request = HttpRequest.newBuilder(URI(mcpUri))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }

    /**
     * The person's browser, as far as the first hop: opens [authorizationUrl],
     * approves, and returns the redirect the authorization server answers with,
     * without following it.
     */
    fun approve(authorizationUrl: String): String {
        val response = client.send(
            HttpRequest.newBuilder(URI(authorizationUrl)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        check(response.statusCode() == 302) { "the authorize page answered ${response.statusCode()}" }
        return response.headers().firstValue("Location").orElseThrow()
    }

    /**
     * The person's browser all the way: approves, then follows the redirect to
     * the loopback listener without waiting for its answer, as a browser that
     * was handed a URL does.
     */
    fun approveAndFollow(authorizationUrl: String) {
        client.sendAsync(
            HttpRequest.newBuilder(URI(approve(authorizationUrl))).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
    }

    override fun close() {
        http.stop(0)
    }

    private fun mcp(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST, DELETE")
            return exchange.reply(405, "")
        }
        val accept = exchange.requestHeaders.getFirst("Accept").orEmpty()
        if ("application/json" !in accept || "text/event-stream" !in accept) return exchange.reply(406, "")

        val bearer = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        if (bearer == null || bearer !in liveTokens) {
            val metadata = " resource_metadata=\"$origin/.well-known/oauth-protected-resource/mcp\""
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer" + if (challengeNamesMetadata) metadata else "")
            return exchange.reply(401, "")
        }
        exchange.reply(200, """{"jsonrpc":"2.0","id":1,"result":{}}""")
    }

    private fun resourceMetadata(exchange: HttpExchange) {
        if (!serveResourceMetadata) return exchange.reply(404, "{}")
        exchange.reply(
            200,
            """{"resource":"$mcpUri","authorization_servers":["$issuer"],"scopes_supported":["$READ","$OPERATE"]}""",
        )
    }

    private fun serverMetadata(exchange: HttpExchange) {
        val optional = buildString {
            if (offerRegistration) append(""","registration_endpoint":"$origin/register"""")
            if (advertiseRevocation) append(""","revocation_endpoint":"$origin/revoke"""")
        }
        val grants =
            if (issueRefreshToken) """["authorization_code","refresh_token"]""" else """["authorization_code"]"""
        exchange.reply(
            200,
            """{"issuer":"$issuer","authorization_endpoint":"$origin/authorize","token_endpoint":"$origin/token",""" +
                """"response_types_supported":["code"],"grant_types_supported":$grants,""" +
                """"code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"],""" +
                """"authorization_response_iss_parameter_supported":$advertiseIss$optional}""",
        )
    }

    private fun register(exchange: HttpExchange) {
        if (!offerRegistration) return exchange.reply(404, "{}")
        registrations += Json.parseToJsonElement(exchange.body()).jsonObject
        val clientId = "fake-registered-client-${registrations.size}"
        clients += clientId
        exchange.reply(201, """{"client_id":"$clientId","token_endpoint_auth_method":"none"}""")
    }

    private fun authorize(exchange: HttpExchange) {
        val request = form(exchange.requestURI.rawQuery.orEmpty())
        authorizeRequests += request
        val valid = request["response_type"] == "code" &&
            request["client_id"].orEmpty() in clients &&
            request["code_challenge_method"] == "S256" &&
            !request["code_challenge"].isNullOrEmpty() &&
            request["resource"] == mcpUri
        if (!valid) return exchange.reply(400, """{"error":"invalid_request"}""")

        val code = "fake-code-${authorizeRequests.size}"
        synchronized(codes) { codes[code] = request }
        val issuerParameter = if (advertiseIss) "&iss=${encode(redirectIssuer ?: issuer)}" else ""
        val location = request.getValue("redirect_uri") +
            "?code=$code&state=${encode(redirectState ?: request["state"].orEmpty())}$issuerParameter"
        exchange.responseHeaders.add("Location", location)
        exchange.reply(302, "")
    }

    private fun token(exchange: HttpExchange) {
        val request = form(exchange.body())
        tokenRequests += request

        val granted = synchronized(codes) { codes.remove(request["code"]) }
        val verifier = request["code_verifier"].orEmpty().toByteArray(Charsets.US_ASCII)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier))
        val valid = granted != null &&
            request["grant_type"] == "authorization_code" &&
            request["client_id"] == granted["client_id"] &&
            request["redirect_uri"] == granted["redirect_uri"] &&
            request["resource"] == mcpUri &&
            challenge == granted["code_challenge"]
        if (granted == null || !valid) return exchange.reply(400, """{"error":"invalid_grant"}""")

        val asked = granted["scope"].orEmpty().split(' ').filter { it.isNotEmpty() }
        val scope = (asked + if (widenScope) listOf(OPERATE) else emptyList()).distinct().joinToString(" ")
        val accessToken = "fake-access-${tokenRequests.size}".also { liveTokens += it }
        val refresh = if (issueRefreshToken) ""","refresh_token":"fake-refresh-${tokenRequests.size}"""" else ""
        exchange.reply(
            200,
            """{"access_token":"$accessToken","token_type":"Bearer","expires_in":$THIRTY_DAYS,""" +
                """"scope":"$scope"$refresh}""",
        )
    }

    private fun revoke(exchange: HttpExchange) {
        if (!advertiseRevocation) return exchange.reply(404, "{}")
        val request = form(exchange.body())
        revocationRequests += request
        liveTokens -= request["token"].orEmpty()
        exchange.reply(200, "")
    }

    private fun HttpExchange.body(): String = requestBody.readBytes().toString(Charsets.UTF_8)

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty()) responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.write(bytes)
    }

    private fun form(encoded: String): Map<String, String> =
        encoded.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val READ = "fake:read"
        const val OPERATE = "fake:operate"
        const val PREREGISTERED_CLIENT = "fake-preregistered-client"
        const val THIRTY_DAYS = 2_592_000L

        private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    }
}
