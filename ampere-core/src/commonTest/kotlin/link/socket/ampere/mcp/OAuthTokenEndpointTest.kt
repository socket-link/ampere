package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OAuthTokenEndpointTest {

    private class Captured {
        var method: HttpMethod? = null
        var url: String? = null
        var form: Parameters? = null
    }

    private fun endpoint(
        captured: Captured = Captured(),
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
    ) = KtorOAuthTokenEndpoint(
        HttpClient(
            MockEngine { call ->
                captured.method = call.method
                captured.url = call.url.toString()
                captured.form = parseQueryString(call.body.toByteArray().decodeToString())
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ),
    )

    private val fakeExchange = OAuthCodeExchange(
        code = "fake-code-1",
        codeVerifier = "fake-verifier-1",
        redirectUri = FAKE_REDIRECT_URI,
        resource = FAKE_MCP_URI,
    )

    private val rotated = """
        {"access_token":"fake-access-1","token_type":"Bearer","expires_in":86399,
         "refresh_token":"fake-refresh-1","scope":"read write"}
    """.trimIndent()

    @Test
    fun `a refresh posts the refresh_token grant with the client credentials as a form`() = runTest {
        val captured = Captured()

        endpoint(captured, body = rotated).refresh(fakeClient, "fake-refresh-0")

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals(fakeClient.tokenEndpoint, captured.url)
        val form = captured.form!!
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("fake-refresh-0", form["refresh_token"])
        assertEquals("fake-client-id", form["client_id"])
        assertEquals("fake-client-secret", form["client_secret"])
    }

    @Test
    fun `a public client sends no secret`() = runTest {
        val captured = Captured()

        endpoint(captured, body = rotated).refresh(fakeClient.copy(clientSecret = null), "fake-refresh-0")

        assertNull(captured.form!!["client_secret"])
        assertEquals("fake-client-id", captured.form!!["client_id"])
    }

    @Test
    fun `a token response with a rotated refresh token is Issued`() = runTest {
        val result = endpoint(body = rotated).refresh(fakeClient, "fake-refresh-0")

        assertEquals(
            OAuthTokenResult.Issued(
                accessToken = "fake-access-1",
                refreshToken = "fake-refresh-1",
                expiresIn = 86_399,
                scopes = setOf("read", "write"),
            ),
            result,
        )
    }

    @Test
    fun `a code exchange posts the authorization_code grant with the verifier and the resource`() = runTest {
        val captured = Captured()

        endpoint(captured, body = rotated).exchangeCode(fakeClient.copy(clientSecret = null), fakeExchange)

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals(fakeClient.tokenEndpoint, captured.url)
        val form = captured.form!!
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("fake-code-1", form["code"])
        assertEquals("fake-verifier-1", form["code_verifier"])
        assertEquals(FAKE_REDIRECT_URI, form["redirect_uri"])
        assertEquals(FAKE_MCP_URI, form["resource"])
        assertEquals("fake-client-id", form["client_id"])
        assertNull(form["client_secret"], "a public client sends no secret")
    }

    @Test
    fun `a code exchange that grants more than was asked for says so and may carry no refresh token`() = runTest {
        val result = endpoint(
            body = """{"access_token":"fake-access-1","token_type":"Bearer","expires_in":2592000,""" +
                """"scope":"read operate"}""",
        ).exchangeCode(fakeClient, fakeExchange)

        assertEquals(
            OAuthTokenResult.Issued(
                accessToken = "fake-access-1",
                refreshToken = null,
                expiresIn = 2_592_000,
                scopes = setOf("read", "operate"),
            ),
            result,
        )
    }

    @Test
    fun `a refused code is Rejected and an outage is Unavailable`() = runTest {
        val refused = endpoint(status = HttpStatusCode.BadRequest, body = """{"error":"invalid_grant"}""")
            .exchangeCode(fakeClient, fakeExchange)
        val down = endpoint(status = HttpStatusCode.BadGateway, body = "bad gateway")
            .exchangeCode(fakeClient, fakeExchange)

        assertEquals(OAuthTokenResult.Rejected("invalid_grant"), refused)
        assertIs<OAuthTokenResult.Unavailable>(down)
        assertFalse("fake-code-1" in refused.toString() + down.toString())
    }

    @Test
    fun `scope is read space-delimited or comma-delimited or as an array`() {
        fun scopes(member: String): Set<String>? {
            val parsed = OAuthTokenResult.parse("{\"access_token\":\"fake-access-1\",$member}")
            return assertIs<OAuthTokenResult.Issued>(parsed).scopes
        }

        assertEquals(setOf("read", "operate"), scopes("\"scope\":\"read operate\""))
        assertEquals(setOf("read", "write"), scopes("\"scope\":\"read,write\""))
        assertEquals(setOf("read", "write"), scopes("\"scope\":[\"read\",\"write\"]"))
        assertNull(scopes("\"scope\":\"\""), "an empty scope states nothing")
        assertNull(scopes("\"scope\":null"))
        assertNull(scopes("\"token_type\":\"Bearer\""))
    }

    @Test
    fun `a revocation posts the token and its hint and a 200 is Revoked`() = runTest {
        val captured = Captured()
        val client = fakeClient.copy(revocationEndpoint = "https://issuer.invalid/oauth/revoke")

        val result = endpoint(captured, body = "").revoke(client, "fake-refresh-0", "refresh_token")

        assertEquals(OAuthRevocationResult.Revoked, result)
        assertEquals("https://issuer.invalid/oauth/revoke", captured.url)
        val form = captured.form!!
        assertEquals("fake-refresh-0", form["token"])
        assertEquals("refresh_token", form["token_type_hint"])
        assertEquals("fake-client-id", form["client_id"])
    }

    @Test
    fun `a revocation the endpoint does not confirm is Failed and quotes no token`() = runTest {
        val client = fakeClient.copy(revocationEndpoint = "https://issuer.invalid/oauth/revoke")
        val unreachable = KtorOAuthTokenEndpoint(HttpClient(MockEngine { error("connection refused") }))

        val notFound = endpoint(status = HttpStatusCode.NotFound, body = "fake-refresh-0 not found")
            .revoke(client, "fake-refresh-0", "refresh_token")
        val down = unreachable.revoke(client, "fake-refresh-0", "refresh_token")

        assertIs<OAuthRevocationResult.Failed>(notFound)
        assertIs<OAuthRevocationResult.Failed>(down)
        assertFalse("fake-refresh-0" in notFound.toString() + down.toString())
    }

    @Test
    fun `a client with no revocation endpoint is Failed without a request`() = runTest {
        val captured = Captured()

        val result = endpoint(captured, body = "").revoke(fakeClient, "fake-refresh-0", "refresh_token")

        assertIs<OAuthRevocationResult.Failed>(result)
        assertNull(captured.url, "no endpoint is guessed at")
    }

    @Test
    fun `an endpoint client written for the refresh grant alone reports the new calls as unsupported`() = runTest {
        val refreshOnly = ScriptedTokenEndpoint { OAuthTokenResult.Rejected("invalid_grant") }

        assertIs<OAuthTokenResult.Unavailable>(refreshOnly.exchangeCode(fakeClient, fakeExchange))
        assertIs<OAuthRevocationResult.Failed>(refreshOnly.revoke(fakeClient, "fake-access-0", "access_token"))
    }

    @Test
    fun `a code exchange names its fields and never the code or the verifier`() {
        val rendered = fakeExchange.toString()

        assertFalse("fake-code-1" in rendered, rendered)
        assertFalse("fake-verifier-1" in rendered, rendered)
        assertTrue(FAKE_REDIRECT_URI in rendered, rendered)
    }

    @Test
    fun `a token response with no refresh token keeps the old one in force`() = runTest {
        val result = endpoint(body = """{"access_token":"fake-access-1","expires_in":"3600"}""")
            .refresh(fakeClient, "fake-refresh-0")

        val issued = assertIs<OAuthTokenResult.Issued>(result)
        assertNull(issued.refreshToken)
        assertEquals(3600, issued.expiresIn)
    }

    @Test
    fun `invalid_grant is Rejected and quotes nothing but the code`() = runTest {
        val result = endpoint(
            status = HttpStatusCode.BadRequest,
            body = """{"error":"invalid_grant","error_description":"refresh token fake-refresh-0 was revoked"}""",
        ).refresh(fakeClient, "fake-refresh-0")

        assertEquals(OAuthTokenResult.Rejected("invalid_grant"), result)
        assertFalse("fake-refresh-0" in result.toString())
    }

    @Test
    fun `an error code is reduced to safe characters`() {
        val result = OAuthTokenResult.parse("""{"error":"invalid_grant\n<script>"}""", status = 400)

        assertEquals(OAuthTokenResult.Rejected("invalid_grantscript"), result)
    }

    @Test
    fun `a 5xx or a 429 is Unavailable whatever the body says`() = runTest {
        val down = endpoint(status = HttpStatusCode.ServiceUnavailable, body = """{"error":"invalid_grant"}""")
            .refresh(fakeClient, "fake-refresh-0")
        val throttled = endpoint(status = HttpStatusCode.TooManyRequests, body = "slow down")
            .refresh(fakeClient, "fake-refresh-0")

        assertIs<OAuthTokenResult.Unavailable>(down)
        assertIs<OAuthTokenResult.Unavailable>(throttled)
    }

    @Test
    fun `a success body that is not a token response is Unavailable and not quoted`() = runTest {
        val result = endpoint(body = """{"accessToken":"fake-access-camel-case"}""")
            .refresh(fakeClient, "fake-refresh-0")
        val garbage = endpoint(body = "fake-access-in-plain-text").refresh(fakeClient, "fake-refresh-0")

        assertIs<OAuthTokenResult.Unavailable>(result)
        assertIs<OAuthTokenResult.Unavailable>(garbage)
        assertFalse("fake-access" in result.toString() + garbage.toString())
    }

    @Test
    fun `an unreachable endpoint is Unavailable`() = runTest {
        val unreachable = KtorOAuthTokenEndpoint(HttpClient(MockEngine { error("connection refused") }))

        val result = unreachable.refresh(fakeClient, "fake-refresh-0")

        val unavailable = assertIs<OAuthTokenResult.Unavailable>(result)
        assertTrue("unreachable" in unavailable.reason, unavailable.reason)
    }

    @Test
    fun `Issued names its fields and never its tokens`() {
        val rendered = OAuthTokenResult.Issued("fake-access-1", "fake-refresh-1", 60).toString()

        assertFalse("fake-access-1" in rendered, rendered)
        assertFalse("fake-refresh-1" in rendered, rendered)
        assertTrue("60" in rendered, rendered)
    }
}
