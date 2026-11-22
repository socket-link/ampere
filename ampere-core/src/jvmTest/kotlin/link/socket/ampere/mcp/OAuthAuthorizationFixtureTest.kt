package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.FixtureAuthorizationServer.Companion.OPERATE
import link.socket.ampere.mcp.FixtureAuthorizationServer.Companion.READ
import link.socket.ampere.mcp.McpAuthorizationException.DiscoveryFailed
import link.socket.ampere.time.MutableClock

/**
 * The whole authorization-code flow over real HTTP (AMPR-516): the production
 * Ktor discovery, registration and token clients against a fixture
 * authorization server, with the JVM loopback listener as the browser step and
 * a scripted "browser" that approves and follows the redirect.
 */
class OAuthAuthorizationFixtureTest {

    private val linkId = LinkId("mcp:fixture")
    private val clock = MutableClock(Instant.parse("2026-11-22T12:00:00Z"))
    private val binding = InMemoryMcpCredentialBinding()
    private val askForRead = McpAuthorizationRequest(scopes = setOf(READ))

    private fun flow(
        fixture: FixtureAuthorizationServer = FixtureAuthorizationServer(),
        test: suspend Flow.() -> Unit,
    ) = runBlocking<Unit> {
        val endpoints = KtorOAuthEndpoints()
        try {
            fixture.use { Flow(it, McpOAuthAuthorizer(binding, endpoints, clock)).test() }
        } finally {
            endpoints.close()
        }
    }

    private inner class Flow(val fixture: FixtureAuthorizationServer, val authorizer: McpOAuthAuthorizer) {

        /** The loopback listener, with a browser that approves and comes back to it. */
        val browser = LoopbackOAuthBrowserStep(openUrl = { fixture.approveAndFollow(it) }, timeout = 20.seconds)

        suspend fun authorize(request: McpAuthorizationRequest = askForRead) =
            authorizer.authorize(linkId, fixture.mcpUri, browser, request)

        suspend fun stored(): McpCredential? = binding.resolve(linkId, fixture.mcpUri).getOrThrow()
    }

    @Test
    fun `a Link is authorized against a spec-current server and the grant is recorded as granted`() = flow {
        val grant = authorize().getOrThrow()

        // What was granted, not what was asked for; and when it stops.
        assertEquals(setOf(READ, OPERATE), grant.scopes)
        assertEquals(setOf(READ), grant.requestedScopes)
        assertEquals(setOf(OPERATE), grant.beyondRequest)
        assertTrue(grant.scopesStatedByServer)
        assertFalse(grant.renewable, "the server issued no refresh token")
        assertEquals(clock.now() + 30.days, grant.stopsWorkingAt)
        assertFalse(grant.revocable, "the server advertises no revocation endpoint")

        // The credential works at the endpoint it was minted for.
        val credential = assertNotNull(stored())
        assertNull(credential.refreshToken)
        assertTrue(fixture.accepts(credential.authToken))

        // Found from the 401, by a request the server answers with one.
        assertEquals("POST /mcp", fixture.fetched.first())
        assertTrue("GET /.well-known/oauth-protected-resource/mcp" in fixture.fetched)
        assertTrue("GET /.well-known/oauth-authorization-server" in fixture.fetched)

        // Registered as a public client on the loopback redirect, and stored with the credential.
        val registration = fixture.registrations.single()
        val redirectUri = registration.getValue("redirect_uris").jsonArray.single().jsonPrimitive.content
        assertTrue(Regex("""http://127\.0\.0\.1:\d+/oauth/callback""").matches(redirectUri), redirectUri)
        assertEquals("none", registration.getValue("token_endpoint_auth_method").jsonPrimitive.content)
        assertEquals("fake-registered-client-1", credential.oauthClient?.clientId)
        assertEquals(redirectUri, credential.oauthClient?.redirectUri)

        // PKCE S256, state, and the resource on both requests. The fixture checked the verifier itself.
        val request = fixture.authorizeRequests.single()
        assertEquals("S256", request["code_challenge_method"])
        assertEquals(fixture.mcpUri, request["resource"])
        assertEquals(READ, request["scope"])
        val exchange = fixture.tokenRequests.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals(fixture.mcpUri, exchange["resource"])
        assertEquals(redirectUri, exchange["redirect_uri"])
        assertNull(exchange["client_secret"])
    }

    @Test
    fun `a 401 that names no metadata is followed by the well-known path`() =
        flow(FixtureAuthorizationServer(challengeNamesMetadata = false)) {
            authorize().getOrThrow()

            assertTrue(fixture.accepts(stored()?.authToken))
        }

    @Test
    fun `a server that offers neither fails typed and names what was missing`() =
        flow(FixtureAuthorizationServer(challengeNamesMetadata = false, serveResourceMetadata = false)) {
            val failure = assertIs<DiscoveryFailed>(authorize().exceptionOrNull())

            assertEquals(DiscoveryFailed.Missing.PROTECTED_RESOURCE_METADATA, failure.missing)
            assertTrue(fixture.authorizeRequests.isEmpty())
            assertNull(stored())
        }

    @Test
    fun `without registration a client id must be supplied and then it is the one used`() =
        flow(FixtureAuthorizationServer(offerRegistration = false)) {
            assertIs<McpAuthorizationException.ClientRegistrationUnavailable>(authorize().exceptionOrNull())
            assertTrue(fixture.authorizeRequests.isEmpty(), "the person is not sent anywhere")

            val supplied = McpAuthorizationRequest(
                scopes = setOf(READ),
                clientId = FixtureAuthorizationServer.PREREGISTERED_CLIENT,
            )
            authorize(supplied).getOrThrow()

            assertTrue(fixture.registrations.isEmpty())
            assertEquals(FixtureAuthorizationServer.PREREGISTERED_CLIENT, fixture.tokenRequests.single()["client_id"])
            assertTrue(fixture.accepts(stored()?.authToken))
        }

    @Test
    fun `a server that issues a refresh token and no extra scope gives a renewable grant as asked`() =
        flow(FixtureAuthorizationServer(issueRefreshToken = true, widenScope = false)) {
            val grant = authorize().getOrThrow()

            assertTrue(grant.renewable)
            assertNull(grant.stopsWorkingAt)
            assertFalse(grant.differsFromRequest)
            assertEquals("fake-refresh-1", stored()?.refreshToken)
        }

    @Test
    fun `a redirect with the wrong state or the wrong iss is refused before the code is sent`() = flow {
        fixture.redirectState = "fake-state-from-another-request"
        assertIs<McpAuthorizationException.StateMismatch>(authorize().exceptionOrNull())

        fixture.redirectState = null
        fixture.redirectIssuer = "https://attacker.invalid"
        assertIs<McpAuthorizationException.IssuerMismatch>(authorize().exceptionOrNull())

        assertTrue(fixture.tokenRequests.isEmpty(), "no code reached the token endpoint")
        assertNull(stored())
    }

    @Test
    fun `revoking through an advertised endpoint ends the token at the server`() =
        flow(FixtureAuthorizationServer(advertiseRevocation = true)) {
            assertTrue(authorize().getOrThrow().revocable)
            val token = stored()?.authToken
            assertTrue(fixture.accepts(token))

            val outcome = authorizer.revoke(linkId, fixture.mcpUri).getOrThrow()

            assertEquals(McpRevocation.Revoked, outcome)
            assertFalse(fixture.accepts(token))
            assertNull(stored())
        }

    @Test
    fun `with no revocation endpoint the credential is removed and the report says the grant remains`() = flow {
        authorize().getOrThrow()
        val token = stored()?.authToken

        val outcome = authorizer.revoke(linkId, fixture.mcpUri).getOrThrow()

        val remains = assertIs<McpRevocation.GrantRemains>(outcome)
        assertEquals(McpRevocation.GrantRemains.Reason.NO_REVOCATION_ENDPOINT, remains.reason)
        assertEquals(clock.now() + 30.days, remains.remainsUntil)
        assertNull(stored())
        // Which is the truth: the server still honours the token, so "revoked" would have been false.
        assertTrue(fixture.accepts(token))
        assertTrue(fixture.revocationRequests.isEmpty(), "no endpoint was guessed at")
    }
}
