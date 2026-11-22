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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Dynamic client registration (RFC 7591) over a mock engine (AMPR-516). */
class OAuthClientRegistrarTest {

    private class Captured {
        var method: HttpMethod? = null
        var url: String? = null
        var contentType: String? = null
        var body: JsonObject? = null
    }

    private val endpoint = "https://auth.invalid/register"
    private val registration = OAuthClientRegistration(
        clientName = "Ampere",
        redirectUri = FAKE_REDIRECT_URI,
        grantTypes = listOf("authorization_code", "refresh_token"),
        scopes = setOf("read", "operate"),
    )

    private fun registrar(
        captured: Captured = Captured(),
        status: HttpStatusCode = HttpStatusCode.Created,
        body: String,
    ) = KtorOAuthClientRegistrar(
        HttpClient(
            MockEngine { call ->
                captured.method = call.method
                captured.url = call.url.toString()
                captured.contentType = call.body.contentType?.toString()
                captured.body = Json.parseToJsonElement(call.body.toByteArray().decodeToString()).jsonObject
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ),
    )

    @Test
    fun `a registration asks for a public client on one redirect URI`() = runTest {
        val captured = Captured()

        registrar(captured, body = """{"client_id":"fake-registered-client"}""").register(endpoint, registration)

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals(endpoint, captured.url)
        assertTrue(captured.contentType.orEmpty().startsWith("application/json"), captured.contentType)
        val body = captured.body!!
        assertEquals("Ampere", body.getValue("client_name").jsonPrimitive.content)
        assertEquals(
            listOf(FAKE_REDIRECT_URI),
            body.getValue("redirect_uris").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("authorization_code", "refresh_token"),
            body.getValue("grant_types").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(listOf("code"), body.getValue("response_types").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("none", body.getValue("token_endpoint_auth_method").jsonPrimitive.content)
        assertEquals("read operate", body.getValue("scope").jsonPrimitive.content)
    }

    @Test
    fun `no scopes leaves the scope member out`() = runTest {
        val captured = Captured()

        registrar(captured, body = """{"client_id":"fake-registered-client"}""")
            .register(endpoint, registration.copy(scopes = emptySet()))

        assertNull(captured.body!!["scope"])
    }

    @Test
    fun `a 201 with a client id is Registered and renders neither id nor secret`() = runTest {
        val result = registrar(
            body = """{"client_id":"fake-registered-client","client_secret":"fake-registered-secret"}""",
        ).register(endpoint, registration)

        assertEquals(OAuthRegistrationResult.Registered("fake-registered-client", "fake-registered-secret"), result)
        assertFalse("fake-registered" in result.toString(), result.toString())
    }

    @Test
    fun `an error answer is Rejected with the code alone`() = runTest {
        val result = registrar(
            status = HttpStatusCode.BadRequest,
            body = """{"error":"invalid_redirect_uri","error_description":"http://127.0.0.1:49152 is not allowed"}""",
        ).register(endpoint, registration)

        assertEquals(OAuthRegistrationResult.Rejected("invalid_redirect_uri"), result)
    }

    @Test
    fun `a 5xx or a body with no client id is Unavailable and quotes nothing`() = runTest {
        val down = registrar(status = HttpStatusCode.ServiceUnavailable, body = "down").register(endpoint, registration)
        val empty = registrar(body = """{"client_secret":"fake-registered-secret"}""").register(endpoint, registration)
        val garbage = registrar(body = "fake-registered-secret").register(endpoint, registration)

        assertIs<OAuthRegistrationResult.Unavailable>(down)
        assertIs<OAuthRegistrationResult.Unavailable>(empty)
        assertIs<OAuthRegistrationResult.Unavailable>(garbage)
        assertFalse("fake-registered-secret" in empty.toString() + garbage.toString())
    }

    @Test
    fun `an unreachable endpoint is Unavailable`() = runTest {
        val unreachable = KtorOAuthClientRegistrar(HttpClient(MockEngine { error("connection refused") }))

        val result = unreachable.register(endpoint, registration)

        val unavailable = assertIs<OAuthRegistrationResult.Unavailable>(result)
        assertTrue("unreachable" in unavailable.reason, unavailable.reason)
    }
}
