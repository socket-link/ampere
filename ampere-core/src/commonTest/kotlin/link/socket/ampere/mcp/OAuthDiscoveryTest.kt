package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.mcp.McpAuthorizationException.DiscoveryFailed
import link.socket.ampere.mcp.McpAuthorizationException.DiscoveryFailed.Missing
import link.socket.ampere.mcp.McpAuthorizationException.MetadataRefused

/**
 * Discovery (RFC 9728, RFC 8414) over a mock engine (AMPR-516): from the 401,
 * by the well-known fallback, and every missing piece failing typed.
 */
class OAuthDiscoveryTest {

    private val resourceMetadataUrl = "https://mcp.invalid/.well-known/oauth-protected-resource/mcp"
    private val serverMetadataUrl = "$FAKE_ISSUER/.well-known/oauth-authorization-server"

    private val resourceMetadata = """
        {"resource":"$FAKE_MCP_URI","authorization_servers":["$FAKE_ISSUER"],
         "scopes_supported":["read","operate"],"bearer_methods_supported":["header"]}
    """.trimIndent()

    /** The authorization server AMPR-516 was written against, by behaviour. */
    private val serverMetadata = """
        {"issuer":"$FAKE_ISSUER",
         "authorization_endpoint":"$FAKE_ISSUER/authorize",
         "token_endpoint":"$FAKE_ISSUER/token",
         "registration_endpoint":"$FAKE_ISSUER/register",
         "response_types_supported":["code"],
         "grant_types_supported":["authorization_code"],
         "code_challenge_methods_supported":["S256"],
         "token_endpoint_auth_methods_supported":["none"],
         "authorization_response_iss_parameter_supported":true}
    """.trimIndent()

    private class Request(val method: HttpMethod, val url: String, val accept: String?, val body: String)

    /**
     * A server made of [documents] by URL. Anything else is a 404, except the
     * MCP endpoint, which answers a POST with a 401 carrying [challenge].
     */
    private inner class Server(
        val documents: Map<String, String> = mapOf(
            resourceMetadataUrl to resourceMetadata,
            serverMetadataUrl to serverMetadata,
        ),
        val challenge: String? = "Bearer resource_metadata=\"$resourceMetadataUrl\"",
        val endpointStatus: HttpStatusCode = HttpStatusCode.Unauthorized,
    ) {
        val requests = mutableListOf<Request>()

        val discovery = KtorOAuthDiscovery(
            HttpClient(
                MockEngine { call ->
                    val url = call.url.toString()
                    requests += Request(
                        method = call.method,
                        url = url,
                        accept = call.headers[HttpHeaders.Accept],
                        body = call.body.toByteArray().decodeToString(),
                    )
                    val document = documents[url]
                    when {
                        url == FAKE_MCP_URI -> respond(
                            content = "",
                            status = endpointStatus,
                            headers = challenge?.let { headersOf(HttpHeaders.WWWAuthenticate, it) } ?: headersOf(),
                        )
                        document != null -> respond(
                            content = document,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                        else -> respond(content = "not found", status = HttpStatusCode.NotFound)
                    }
                },
            ),
        )

        val fetched: List<String>
            get() = requests.filter { it.method == HttpMethod.Get }.map { it.url }

        suspend fun discover(wwwAuthenticate: String? = null) = discovery.discover(FAKE_MCP_URI, wwwAuthenticate)
    }

    private fun Server.without(vararg members: String): Map<String, String> {
        var edited = serverMetadata
        members.forEach { member -> edited = edited.replace(Regex("""\s*"$member":("[^"]*"|\[[^\]]*\]|true),?"""), "") }
        return documents + (serverMetadataUrl to edited.replace(Regex(""",\s*\}"""), "}"))
    }

    @Test
    fun `discovery goes from the 401 to the authorization server`() = runTest {
        val server = Server()

        val found = server.discover().getOrThrow()

        assertEquals(
            McpAuthorizationServer(
                mcpUri = FAKE_MCP_URI,
                resource = FAKE_MCP_URI,
                issuer = FAKE_ISSUER,
                authorizationEndpoint = "$FAKE_ISSUER/authorize",
                tokenEndpoint = "$FAKE_ISSUER/token",
                registrationEndpoint = "$FAKE_ISSUER/register",
                revocationEndpoint = null,
                scopesSupported = setOf("read", "operate"),
                challengedScopes = null,
                issParameterSupported = true,
                refreshTokenGrantSupported = false,
            ),
            found,
        )
        assertEquals(listOf(resourceMetadataUrl, serverMetadataUrl), server.fetched)
    }

    @Test
    fun `the probe is a JSON-RPC POST a spec-current server answers with its 401`() = runTest {
        val server = Server()

        server.discover().getOrThrow()

        val probe = server.requests.first()
        assertEquals(HttpMethod.Post, probe.method, "a bare GET is a 405 on a spec-current server")
        assertEquals(FAKE_MCP_URI, probe.url)
        assertEquals("application/json, text/event-stream", probe.accept, "without it the answer is a 406")
        assertTrue("\"jsonrpc\":\"2.0\"" in probe.body && "\"method\":\"ping\"" in probe.body, probe.body)
    }

    @Test
    fun `a 401 the caller already saw is used and the endpoint is not asked again`() = runTest {
        val server = Server(challenge = null)

        val found = server.discover("Bearer realm=\"mcp\", resource_metadata=\"$resourceMetadataUrl\", scope=\"read\"")
            .getOrThrow()

        assertEquals(setOf("read"), found.challengedScopes)
        assertTrue(server.requests.none { it.url == FAKE_MCP_URI }, "no probe was needed")
    }

    @Test
    fun `a 401 that names no metadata falls back to the well-known path`() = runTest {
        val server = Server(challenge = "Bearer realm=\"mcp\"")

        server.discover().getOrThrow()

        assertEquals(resourceMetadataUrl, server.fetched.first())
    }

    @Test
    fun `the root well-known path is tried after the path-specific one`() = runTest {
        val rootUrl = "https://mcp.invalid/.well-known/oauth-protected-resource"
        val server = Server(
            documents = mapOf(rootUrl to resourceMetadata, serverMetadataUrl to serverMetadata),
            challenge = null,
            endpointStatus = HttpStatusCode.OK,
        )

        server.discover().getOrThrow()

        assertEquals(listOf(resourceMetadataUrl, rootUrl, serverMetadataUrl), server.fetched)
    }

    @Test
    fun `a resource_metadata URL that does not answer falls back to the well-known path`() = runTest {
        val server = Server(challenge = "Bearer resource_metadata=\"https://mcp.invalid/moved\"")

        server.discover().getOrThrow()

        assertEquals(listOf("https://mcp.invalid/moved", resourceMetadataUrl, serverMetadataUrl), server.fetched)
    }

    @Test
    fun `a server that offers neither fails typed and names the protected-resource metadata`() = runTest {
        val server = Server(documents = emptyMap(), challenge = null)

        val failure = assertIs<DiscoveryFailed>(server.discover().exceptionOrNull())

        assertEquals(Missing.PROTECTED_RESOURCE_METADATA, failure.missing)
        assertEquals(FAKE_MCP_URI, failure.mcpUri)
        assertTrue("resource_metadata" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `metadata that lists no authorization server fails typed`() = runTest {
        val server = Server(documents = mapOf(resourceMetadataUrl to """{"resource":"$FAKE_MCP_URI"}"""))

        val failure = assertIs<DiscoveryFailed>(server.discover().exceptionOrNull())

        assertEquals(Missing.AUTHORIZATION_SERVER, failure.missing)
    }

    @Test
    fun `an authorization server with no metadata fails typed after every well-known path`() = runTest {
        val server = Server(documents = mapOf(resourceMetadataUrl to resourceMetadata))

        val failure = assertIs<DiscoveryFailed>(server.discover().exceptionOrNull())

        assertEquals(Missing.AUTHORIZATION_SERVER_METADATA, failure.missing)
        assertEquals(
            listOf(resourceMetadataUrl, serverMetadataUrl, "$FAKE_ISSUER/.well-known/openid-configuration"),
            server.fetched,
        )
    }

    @Test
    fun `an issuer with a path is looked up by RFC 8414 and both OpenID forms`() = runTest {
        val issuer = "$FAKE_ISSUER/tenant"
        val openId = "$FAKE_ISSUER/tenant/.well-known/openid-configuration"
        val server = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata.replace("\"$FAKE_ISSUER\"", "\"$issuer\""),
                openId to serverMetadata.replace("\"issuer\":\"$FAKE_ISSUER\"", "\"issuer\":\"$issuer\""),
            ),
        )

        val found = server.discover().getOrThrow()

        assertEquals(issuer, found.issuer)
        assertEquals(
            listOf(
                "$FAKE_ISSUER/.well-known/oauth-authorization-server/tenant",
                "$FAKE_ISSUER/.well-known/openid-configuration/tenant",
                openId,
            ),
            server.fetched.drop(1),
            "the root documents describe another issuer and are never asked for",
        )
    }

    @Test
    fun `each missing piece of the authorization server fails typed`() = runTest {
        suspend fun missing(vararg members: String): Missing {
            val base = Server()
            val server = Server(documents = base.without(*members))
            return assertIs<DiscoveryFailed>(server.discover().exceptionOrNull()).missing
        }

        assertEquals(Missing.AUTHORIZATION_ENDPOINT, missing("authorization_endpoint"))
        assertEquals(Missing.TOKEN_ENDPOINT, missing("token_endpoint"))
        assertEquals(Missing.PKCE_S256, missing("code_challenge_methods_supported"))
    }

    @Test
    fun `plain-only PKCE and a missing authorization_code grant are refused`() = runTest {
        val plain = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata,
                serverMetadataUrl to serverMetadata.replace("[\"S256\"]", "[\"plain\"]"),
            ),
        )
        val noCodeGrant = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata,
                serverMetadataUrl to serverMetadata.replace("[\"authorization_code\"]", "[\"client_credentials\"]"),
            ),
        )

        assertEquals(Missing.PKCE_S256, assertIs<DiscoveryFailed>(plain.discover().exceptionOrNull()).missing)
        assertEquals(
            Missing.AUTHORIZATION_CODE_GRANT,
            assertIs<DiscoveryFailed>(noCodeGrant.discover().exceptionOrNull()).missing,
        )
    }

    @Test
    fun `absent grant types mean the RFC 8414 default and no refresh grant`() = runTest {
        val base = Server()
        val server = Server(documents = base.without("grant_types_supported"))

        val found = server.discover().getOrThrow()

        assertFalse(found.refreshTokenGrantSupported)
    }

    @Test
    fun `revocation and refresh are reported when the server advertises them`() = runTest {
        val server = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata,
                serverMetadataUrl to serverMetadata
                    .replace("[\"authorization_code\"]", "[\"authorization_code\",\"refresh_token\"]")
                    .replace("\"issuer\"", "\"revocation_endpoint\":\"$FAKE_ISSUER/revoke\",\"issuer\""),
            ),
        )

        val found = server.discover().getOrThrow()

        assertEquals("$FAKE_ISSUER/revoke", found.revocationEndpoint)
        assertTrue(found.refreshTokenGrantSupported)
    }

    @Test
    fun `metadata describing another resource is refused`() = runTest {
        val server = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata.replace(FAKE_MCP_URI, "https://elsewhere.invalid/mcp"),
                serverMetadataUrl to serverMetadata,
            ),
        )

        val failure = assertIs<MetadataRefused>(server.discover().exceptionOrNull())

        assertEquals(MetadataRefused.Reason.RESOURCE_MISMATCH, failure.reason)
    }

    @Test
    fun `a resource that is a parent of the endpoint is accepted and sent as stated`() = runTest {
        val server = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata.replace("\"$FAKE_MCP_URI\"", "\"https://mcp.invalid\""),
                serverMetadataUrl to serverMetadata,
            ),
        )

        assertEquals("https://mcp.invalid", server.discover().getOrThrow().resource)
    }

    @Test
    fun `metadata with no resource member sends the endpoint itself`() = runTest {
        val server = Server(
            documents = mapOf(
                resourceMetadataUrl to """{"authorization_servers":["$FAKE_ISSUER"]}""",
                serverMetadataUrl to serverMetadata,
            ),
        )

        val found = server.discover().getOrThrow()

        assertEquals(FAKE_MCP_URI, found.resource)
        assertTrue(found.scopesSupported.isEmpty())
    }

    @Test
    fun `authorization server metadata stating another issuer is refused`() = runTest {
        val server = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata,
                serverMetadataUrl to serverMetadata.replace("\"$FAKE_ISSUER\",", "\"https://other.invalid\","),
            ),
        )

        val failure = assertIs<MetadataRefused>(server.discover().exceptionOrNull())

        assertEquals(MetadataRefused.Reason.ISSUER_MISMATCH, failure.reason)
    }

    @Test
    fun `a plain http OAuth URL off the loopback is refused`() = runTest {
        val insecureIssuer = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata.replace(FAKE_ISSUER, "http://auth.invalid"),
                serverMetadataUrl to serverMetadata,
            ),
        )
        val insecureEndpoint = Server(
            documents = mapOf(
                resourceMetadataUrl to resourceMetadata,
                serverMetadataUrl to serverMetadata.replace("$FAKE_ISSUER/token", "http://auth.invalid/token"),
            ),
        )
        val insecureChallenge = Server(challenge = "Bearer resource_metadata=\"http://127.example.invalid/prm\"")

        listOf(insecureIssuer, insecureEndpoint, insecureChallenge).forEach { server ->
            val failure = assertIs<MetadataRefused>(server.discover().exceptionOrNull())
            assertEquals(MetadataRefused.Reason.INSECURE_URL, failure.reason)
        }
    }

    @Test
    fun `an endpoint that does not answer or is not a URL fails typed`() = runTest {
        val unreachable = KtorOAuthDiscovery(HttpClient(MockEngine { error("connection refused") }))

        val down = assertIs<DiscoveryFailed>(unreachable.discover(FAKE_MCP_URI).exceptionOrNull())
        val notAUrl = assertIs<DiscoveryFailed>(unreachable.discover("stdio:work-source").exceptionOrNull())

        assertEquals(Missing.MCP_ENDPOINT, down.missing)
        assertEquals(Missing.MCP_ENDPOINT, notAUrl.missing)
    }

    @Test
    fun `a bearer challenge is read whether quoted or bare and with escapes`() {
        val quoted = BearerChallenge.parse(
            "Bearer realm=\"a \\\"quoted\\\" realm\", " +
                "resource_metadata=\"https://mcp.invalid/prm\", scope=\"read operate\"",
        )
        val bare = BearerChallenge.parse("Bearer resource_metadata=https://mcp.invalid/prm")

        assertEquals("https://mcp.invalid/prm", quoted?.resourceMetadata)
        assertEquals(setOf("read", "operate"), quoted?.scopes)
        assertEquals("https://mcp.invalid/prm", bare?.resourceMetadata)
        assertNull(bare?.scopes)
        assertNull(BearerChallenge.parse("Bearer realm=\"mcp\""))
        assertNull(BearerChallenge.parse("Basic"))
    }

    @Test
    fun `loopback means a literal and never a name that starts like one`() {
        listOf("localhost", "127.0.0.1", "127.8.9.10", "[::1]", "::1").forEach {
            assertTrue(it.isLoopback(), it)
        }
        listOf("127.example.invalid", "mcp.invalid", "10.0.0.1", "0.0.0.0").forEach {
            assertFalse(it.isLoopback(), it)
        }
    }
}
