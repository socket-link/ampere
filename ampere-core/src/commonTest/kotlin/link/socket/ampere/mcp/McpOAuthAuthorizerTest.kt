package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.McpAuthorizationException.DiscoveryFailed
import link.socket.ampere.time.MutableClock

/**
 * The authorization-code flow with the browser step faked (AMPR-516): what is
 * sent, what is refused, and what is stored.
 */
class McpOAuthAuthorizerTest {

    private val linkId = LinkId("mcp:work-source")
    private val clock = MutableClock(Instant.parse("2026-11-22T12:00:00Z"))
    private val server = FakeAuthorizationServer()
    private val browser = FakeBrowserStep(server)
    private val binding = RecordingBinding()

    private val authorizer = McpOAuthAuthorizer(
        binding = binding,
        discovery = server,
        registrar = server,
        tokenEndpoint = server,
        clock = clock,
    )

    private suspend fun authorize(request: McpAuthorizationRequest = McpAuthorizationRequest(scopes = setOf("read"))) =
        authorizer.authorize(linkId, FAKE_MCP_URI, browser, request)

    private suspend fun stored(): McpCredential? = binding.resolve(linkId, FAKE_MCP_URI).getOrThrow()

    @Test
    fun `the authorization request carries PKCE S256 and a state and the resource`() = runTest {
        authorize().getOrThrow()

        val request = server.authorizationRequests.single()
        assertEquals("code", request["response_type"])
        assertEquals("fake-registered-client-1", request["client_id"])
        assertEquals(FAKE_REDIRECT_URI, request["redirect_uri"])
        assertEquals("S256", request["code_challenge_method"])
        assertEquals(FAKE_MCP_URI, request["resource"])
        assertEquals("read", request["scope"])
        assertTrue(request.getValue("state").length >= 22, "state should carry at least 128 bits")

        val exchange = server.exchanges.single()
        assertEquals(PkceChallenge.of(exchange.codeVerifier).challenge, request["code_challenge"])
        assertEquals(FAKE_MCP_URI, exchange.resource)
        assertEquals(FAKE_REDIRECT_URI, exchange.redirectUri)
        assertNotEquals(exchange.codeVerifier, request["code_challenge"], "the verifier itself is never sent out")
    }

    @Test
    fun `each authorization uses a fresh state and verifier`() = runTest {
        authorize().getOrThrow()
        authorize().getOrThrow()

        val (first, second) = server.authorizationRequests
        assertNotEquals(first["state"], second["state"])
        assertNotEquals(first["code_challenge"], second["code_challenge"])
    }

    @Test
    fun `the grant is recorded as granted when the server grants more than was asked for`() = runTest {
        val grant = authorize().getOrThrow()

        assertEquals(setOf("read", "operate"), grant.scopes)
        assertEquals(setOf("read"), grant.requestedScopes)
        assertTrue(grant.scopesStatedByServer)
        assertEquals(setOf("operate"), grant.beyondRequest)
        assertTrue(grant.differsFromRequest)
        assertEquals(clock.now() + 30.days, grant.expiresAt)

        val credential = stored()!!
        assertEquals(
            McpGrantedScope(setOf("read", "operate"), setOf("read"), statedByServer = true),
            credential.grantedScope,
        )
        assertEquals(grant, credential.grant, "the grant reads off the stored credential, without its token")
        assertFalse("fake-access" in grant.toString(), grant.toString())
    }

    @Test
    fun `a token response that states no scope records the request as granted`() = runTest {
        server.grantedScopes = null

        val grant = authorize().getOrThrow()

        assertEquals(setOf("read"), grant.scopes)
        assertFalse(grant.scopesStatedByServer)
        assertFalse(grant.differsFromRequest)
    }

    @Test
    fun `a grant narrower than the request says what was withheld`() = runTest {
        server.grantedScopes = { setOf("read") }

        val grant = authorize(McpAuthorizationRequest(scopes = setOf("read", "operate"))).getOrThrow()

        assertEquals(setOf("operate"), grant.withheld)
        assertTrue(grant.beyondRequest.isEmpty())
    }

    @Test
    fun `no refresh token leaves a credential that stops at its expiry`() = runTest {
        val grant = authorize().getOrThrow()

        assertFalse(grant.renewable)
        assertEquals(clock.now() + 30.days, grant.stopsWorkingAt)
        val credential = stored()!!
        assertNull(credential.refreshToken)
        assertFalse(credential.isRefreshable)
        assertFalse(credential.needsReauthorization(clock.now()))
        assertTrue(credential.needsReauthorization(clock.now() + 30.days))
    }

    @Test
    fun `a refresh token makes the grant renewable`() = runTest {
        server.issueRefreshToken = true

        val grant = authorize().getOrThrow()

        assertTrue(grant.renewable)
        assertNull(grant.stopsWorkingAt)
        assertEquals("fake-refresh-1", stored()?.refreshToken)
        assertEquals(listOf("authorization_code", "refresh_token"), server.registrations.single().grantTypes)
    }

    @Test
    fun `the registered client is stored with the credential`() = runTest {
        server.revocationOffered = true

        authorize().getOrThrow()

        assertEquals(
            McpOAuthClient(
                tokenEndpoint = "$FAKE_ISSUER/token",
                clientId = "fake-registered-client-1",
                revocationEndpoint = "$FAKE_ISSUER/revoke",
                redirectUri = FAKE_REDIRECT_URI,
            ),
            stored()?.oauthClient,
        )
        val registration = server.registrations.single()
        assertEquals(FAKE_REDIRECT_URI, registration.redirectUri)
        assertEquals(listOf("authorization_code"), registration.grantTypes)
        assertEquals(McpAuthorizationRequest.DEFAULT_CLIENT_NAME, registration.clientName)
    }

    @Test
    fun `a supplied client id skips registration`() = runTest {
        authorize(McpAuthorizationRequest(scopes = setOf("read"), clientId = "fake-supplied-client")).getOrThrow()

        assertTrue(server.registrations.isEmpty())
        assertEquals("fake-supplied-client", server.authorizationRequests.single()["client_id"])
        assertEquals("fake-supplied-client", stored()?.oauthClient?.clientId)
    }

    @Test
    fun `no registration endpoint and no client id fails typed`() = runTest {
        server.registrationOffered = false

        val failure = authorize().exceptionOrNull()

        assertIs<McpAuthorizationException.ClientRegistrationUnavailable>(failure)
        assertTrue(browser.shown.isEmpty(), "the person is not sent anywhere")
        assertEquals(1, browser.closed, "the browser session is released")
        assertNull(stored())
    }

    @Test
    fun `a refused registration fails typed with the error code alone`() = runTest {
        server.registrationAnswer = OAuthRegistrationResult.Rejected("invalid_redirect_uri")

        val failure = assertIs<McpAuthorizationException.ClientRegistrationFailed>(authorize().exceptionOrNull())

        assertTrue(failure.refused)
        assertEquals("invalid_redirect_uri", failure.reason)
        assertNull(stored())
    }

    @Test
    fun `a stored client is reused on the same redirect URI and registered again on another`() = runTest {
        authorize().getOrThrow()
        authorize().getOrThrow()
        assertEquals(1, server.registrations.size, "same redirect URI: the stored client is used again")
        assertEquals("fake-registered-client-1", server.authorizationRequests.last()["client_id"])

        val elsewhere = FakeBrowserStep(server, redirectUri = "http://127.0.0.1:49153/oauth/callback")
        authorizer.authorize(linkId, FAKE_MCP_URI, elsewhere).getOrThrow()

        assertEquals(2, server.registrations.size, "a registered client is held to its redirect URI")
        assertEquals("fake-registered-client-2", stored()?.oauthClient?.clientId)
    }

    @Test
    fun `without registration a stored client is used on whatever redirect URI it has`() = runTest {
        binding.bind(
            linkId,
            FAKE_MCP_URI,
            McpCredential(
                authToken = "fake-seeded",
                oauthClient = McpOAuthClient("$FAKE_ISSUER/token", "fake-seeded-client"),
            ),
        ).getOrThrow()
        server.registrationOffered = false

        authorize().getOrThrow()

        assertEquals("fake-seeded-client", server.authorizationRequests.single()["client_id"])
        assertEquals("fake-access-1", stored()?.authToken)
    }

    @Test
    fun `with no scopes asked for the challenge decides and then the resource metadata`() = runTest {
        server.challengedScopes = setOf("read")
        authorize(McpAuthorizationRequest()).getOrThrow()
        assertEquals("read", server.authorizationRequests.last()["scope"])

        server.challengedScopes = null
        authorize(McpAuthorizationRequest()).getOrThrow()
        assertEquals("read operate", server.authorizationRequests.last()["scope"])

        server.scopesSupported = emptySet()
        authorize(McpAuthorizationRequest()).getOrThrow()
        assertNull(server.authorizationRequests.last()["scope"], "nothing to ask for: the parameter is left out")
    }

    @Test
    fun `a 401 the host already saw is handed to discovery`() = runTest {
        val challenge = "Bearer resource_metadata=\"https://mcp.invalid/prm\""

        authorize(McpAuthorizationRequest(wwwAuthenticate = challenge)).getOrThrow()

        assertEquals(FAKE_MCP_URI to challenge, server.discoveries.single())
    }

    @Test
    fun `a discovery failure is the authorization failure and opens no browser`() = runTest {
        server.discoveryFailure =
            DiscoveryFailed(FAKE_MCP_URI, DiscoveryFailed.Missing.PKCE_S256, "It advertises plain.")

        val failure = assertIs<DiscoveryFailed>(authorize().exceptionOrNull())

        assertEquals(DiscoveryFailed.Missing.PKCE_S256, failure.missing)
        assertEquals(0, browser.sessions)
    }

    @Test
    fun `a wrong state is refused and the code is never sent`() = runTest {
        browser.tamper = { it.replace(Regex("state=[^&]*"), "state=fake-state-from-another-request") }

        val failure = authorize().exceptionOrNull()

        assertIs<McpAuthorizationException.StateMismatch>(failure)
        assertTrue(server.exchanges.isEmpty(), "a redirect that is not ours buys nothing")
        assertNull(stored())
        assertEquals(1, browser.closed)
    }

    @Test
    fun `a missing state is refused`() = runTest {
        browser.tamper = { it.replace(Regex("&state=[^&]*"), "") }

        assertIs<McpAuthorizationException.StateMismatch>(authorize().exceptionOrNull())
        assertTrue(server.exchanges.isEmpty())
    }

    @Test
    fun `a wrong iss is refused and the code is never sent`() = runTest {
        browser.tamper = { it.replace("iss=$FAKE_ISSUER", "iss=https://attacker.invalid") }

        val failure = assertIs<McpAuthorizationException.IssuerMismatch>(authorize().exceptionOrNull())

        assertTrue(failure.presented)
        assertEquals(FAKE_ISSUER, failure.expectedIssuer)
        assertFalse("attacker.invalid" in failure.message.orEmpty(), "the presented issuer is not quoted")
        assertTrue(server.exchanges.isEmpty())
        assertNull(stored())
    }

    @Test
    fun `a missing iss is refused when the server advertises it`() = runTest {
        browser.tamper = { it.replace("&iss=$FAKE_ISSUER", "") }

        val failure = assertIs<McpAuthorizationException.IssuerMismatch>(authorize().exceptionOrNull())

        assertFalse(failure.presented)
        assertTrue(server.exchanges.isEmpty())
    }

    @Test
    fun `a server that does not advertise iss may leave it out but not get it wrong`() = runTest {
        server.issAdvertised = false
        authorize().getOrThrow()

        browser.tamper = { "$it&iss=https://attacker.invalid" }
        assertIs<McpAuthorizationException.IssuerMismatch>(authorize().exceptionOrNull())
    }

    @Test
    fun `a declined consent is reported with the error code reduced to safe characters`() = runTest {
        server.denyWith = "access_denied"
        val denied = assertIs<McpAuthorizationException.AuthorizationDenied>(authorize().exceptionOrNull())
        assertEquals("access_denied", denied.error)

        server.denyWith = "access_denied%0A%3Cscript%3E"
        val smuggled = assertIs<McpAuthorizationException.AuthorizationDenied>(authorize().exceptionOrNull())
        assertEquals("access_deniedscript", smuggled.error)
        assertTrue(server.exchanges.isEmpty())
    }

    @Test
    fun `a redirect with neither a code nor an error is refused`() = runTest {
        browser.tamper = { it.replace(Regex("code=[^&]*&"), "") }

        assertIs<McpAuthorizationException.NoAuthorizationCode>(authorize().exceptionOrNull())
    }

    @Test
    fun `a refused or unanswered code exchange fails typed and stores nothing`() = runTest {
        server.tokenAnswer = OAuthTokenResult.Rejected("invalid_grant")
        val rejected = assertIs<McpAuthorizationException.TokenRejected>(authorize().exceptionOrNull())
        assertEquals("invalid_grant", rejected.error)

        server.tokenAnswer = OAuthTokenResult.Unavailable("token endpoint answered 503")
        assertIs<McpAuthorizationException.TokenUnavailable>(authorize().exceptionOrNull())

        assertNull(stored())
        assertTrue(binding.binds.isEmpty())
    }

    @Test
    fun `a browser step that cannot start or brings nothing back fails typed`() = runTest {
        browser.beginFailure = IllegalStateException("no port to listen on")
        assertIs<McpAuthorizationException.BrowserStepFailed>(authorize().exceptionOrNull())

        browser.beginFailure = null
        browser.authorizeFailure = IllegalStateException("the person closed the window")
        val failure = assertIs<McpAuthorizationException.BrowserStepFailed>(authorize().exceptionOrNull())

        assertFalse("closed the window" in failure.message.orEmpty(), "the host's message stays on the cause")
        assertEquals(1, browser.closed)
        assertNull(stored())
    }

    @Test
    fun `a failed authorization leaves the stored credential as it was`() = runTest {
        authorize().getOrThrow()
        val before = stored()

        server.tokenAnswer = OAuthTokenResult.Rejected("invalid_grant")
        authorize()

        assertEquals(before, stored())
        assertEquals(0, binding.unbinds)
    }

    @Test
    fun `authorizing again replaces the credential in one bind and never unbinds`() = runTest {
        authorize().getOrThrow()
        authorize().getOrThrow()

        assertEquals(listOf("fake-access-1", "fake-access-2"), binding.binds.map { it.authToken })
        assertEquals(0, binding.unbinds, "the store always holds the old credential or the new one")
        assertEquals("fake-access-2", stored()?.authToken)
    }

    @Test
    fun `a write that fails is retried and then reported without the token`() = runTest {
        binding.refuseBinds = 2
        authorize().getOrThrow()
        assertEquals("fake-access-1", stored()?.authToken)

        binding.refuseBinds = 3
        val failure = assertIs<McpAuthorizationException.PersistFailed>(authorize().exceptionOrNull())
        assertFalse("fake-access" in failure.message.orEmpty(), failure.message)
        assertEquals("fake-access-1", stored()?.authToken, "the earlier credential is still there")
    }

    @Test
    fun `no failure and no rendering carries the code the verifier the state or a token`() = runTest {
        authorizer.randomBytes = { count -> ByteArray(count) { 7 } }
        val verifier = base64Url(ByteArray(48) { 7 })
        val state = base64Url(ByteArray(24) { 7 })

        val failures = mutableListOf<Throwable>()
        suspend fun failing(arrange: () -> Unit) {
            arrange()
            failures += authorize().exceptionOrNull() ?: error("expected a failure")
            browser.tamper = { it }
            server.tokenAnswer = null
            server.denyWith = null
            binding.refuseBinds = 0
        }
        failing { browser.tamper = { it.replace("state=", "state=x") } }
        failing { browser.tamper = { it.replace("iss=", "iss=x") } }
        failing { browser.tamper = { it.replace("code=", "kode=") } }
        failing { server.denyWith = "access_denied" }
        failing { server.tokenAnswer = OAuthTokenResult.Rejected("invalid_grant") }
        failing { server.tokenAnswer = OAuthTokenResult.Unavailable("token endpoint answered 503") }
        failing { binding.refuseBinds = 3 }
        authorize().getOrThrow()

        // The secrets really were in play.
        assertEquals(verifier, server.exchanges.last().codeVerifier)
        assertEquals(state, server.authorizationRequests.last()["state"])
        val exchange = server.exchanges.last()

        val renderings = buildList {
            failures.forEach { failure -> generateSequence(failure) { it.cause }.forEach { add(it.toString()) } }
            add(exchange.toString())
            add(OAuthRedirect(server.approve(browser.shown.last())).toString())
            add(PkceChallenge.of(verifier).toString())
            add(stored().toString())
            add(stored()!!.grant.toString())
            add(McpAuthorizationRequest(clientId = "fake-supplied-client", clientSecret = "fake-secret").toString())
            add(OAuthRegistrationResult.Registered("fake-registered-client-1", "fake-secret").toString())
        }
        val secrets = listOf(
            verifier,
            state,
            "fake-code-",
            "fake-access-",
            "fake-refresh-",
            "fake-registered-client",
            "fake-supplied-client",
            "fake-secret",
        )
        secrets.forEach { secret ->
            renderings.forEach { rendering ->
                assertFalse(secret in rendering, "'$secret' leaked into: $rendering")
            }
        }
    }

    @Test
    fun `revoking through an advertised endpoint revokes both tokens and removes the credential`() = runTest {
        server.revocationOffered = true
        server.issueRefreshToken = true
        authorize().getOrThrow()

        val outcome = authorizer.revoke(linkId, FAKE_MCP_URI).getOrThrow()

        assertEquals(McpRevocation.Revoked, outcome)
        assertEquals(listOf("fake-refresh-1" to "refresh_token", "fake-access-1" to "access_token"), server.revocations)
        assertNull(stored())
    }

    @Test
    fun `with no revocation endpoint the report says the grant remains and until when`() = runTest {
        val grant = authorize().getOrThrow()
        assertFalse(grant.revocable)

        val outcome = authorizer.revoke(linkId, FAKE_MCP_URI).getOrThrow()

        val remains = assertIs<McpRevocation.GrantRemains>(outcome)
        assertEquals(McpRevocation.GrantRemains.Reason.NO_REVOCATION_ENDPOINT, remains.reason)
        assertEquals(clock.now() + 30.days, remains.remainsUntil)
        assertTrue(server.revocations.isEmpty(), "no endpoint is guessed at")
        assertNull(stored(), "the stored credential is gone all the same")
    }

    @Test
    fun `a revocation the endpoint does not confirm is reported as a grant that remains`() = runTest {
        server.revocationOffered = true
        server.issueRefreshToken = true
        authorize().getOrThrow()
        server.revocationAnswer = OAuthRevocationResult.Failed("revocation endpoint answered 503")

        val remains = assertIs<McpRevocation.GrantRemains>(authorizer.revoke(linkId, FAKE_MCP_URI).getOrThrow())

        assertEquals(McpRevocation.GrantRemains.Reason.REVOCATION_FAILED, remains.reason)
        assertEquals("revocation endpoint answered 503", remains.detail)
        assertNull(remains.remainsUntil, "a grant with a refresh token has no stated end")
        assertNull(stored())
    }

    @Test
    fun `revoking with nothing bound says so`() = runTest {
        assertEquals(McpRevocation.NothingBound, authorizer.revoke(linkId, FAKE_MCP_URI).getOrThrow())
    }
}
