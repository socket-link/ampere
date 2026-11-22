package link.socket.ampere.mcp

/**
 * The one part of an authorization-code flow Ampere cannot do: put a URL in
 * front of the person and catch the redirect that comes back (AMPR-516).
 *
 * The host supplies it, because how differs by host. A desktop app listens on
 * a loopback port and opens the system browser; a mobile app opens a browser
 * tab and receives its own redirect scheme. Ampere owns everything on either
 * side of the hand-off: discovery, the client, PKCE, `state`, the issuer
 * check, the code exchange and the stored credential. `commonMain` opens no
 * browser and binds no socket; the JVM default is `LoopbackOAuthBrowserStep`.
 *
 * It is two calls rather than one because the redirect URI has to be known
 * before the URL can be built: it is registered with the client and sent in
 * the authorization request, and a loopback listener only knows its port once
 * it has bound it.
 */
interface OAuthBrowserStep {

    /**
     * Get ready to receive one redirect, and say where it will arrive. The
     * session is [closed][OAuthBrowserSession.close] by the caller whatever
     * happens next.
     */
    suspend fun begin(): Result<OAuthBrowserSession>
}

/** One authorization's worth of [OAuthBrowserStep]: one redirect URI, one redirect. */
interface OAuthBrowserSession {

    /**
     * Where the authorization server should send the person back: a loopback
     * URL (RFC 8252 §7.3) or the app's own scheme (§7.1).
     */
    val redirectUri: String

    /**
     * Show the person [authorizationUrl] and suspend until the redirect comes
     * back, however long they take. Fail when it cannot: the browser would not
     * open, they closed it, or the host stopped waiting.
     *
     * The URL carries this request's `state` and PKCE challenge. Neither is a
     * credential, but a host has no reason to log either. A failure must not
     * quote the redirect: it carries the authorization code.
     */
    suspend fun authorize(authorizationUrl: String): Result<OAuthRedirect>

    /** Release whatever [OAuthBrowserStep.begin] took: a socket, a listener registration. Safe to call twice. */
    suspend fun close()
}

/**
 * The redirect the authorization server sent the person back with, in full:
 * the redirect URI and its query.
 *
 * The query carries the authorization code, which is a credential until it is
 * redeemed, so [toString] shows none of it. Only the query is read; Ampere
 * checks `state` and `iss` against the request, not the host.
 */
class OAuthRedirect(val uri: String) {

    override fun toString(): String = "OAuthRedirect(<redacted>)"
}
