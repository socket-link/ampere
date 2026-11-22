package link.socket.ampere.mcp

/**
 * Why a Link could not be authorized through its MCP server's
 * authorization-code flow (AMPR-516).
 *
 * Each variant names the one thing that was missing or refused, because the
 * remedies differ: a server that offers no metadata needs a different server
 * or a hand-seeded credential, a refused consent needs the person, and a token
 * endpoint that was down only needs another try.
 *
 * No message carries an authorization code, a PKCE verifier, a `state` value,
 * a token or a client id. The redirect a browser step hands back is never
 * quoted, whatever was wrong with it.
 */
sealed class McpAuthorizationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The MCP endpoint the authorization was for. */
    abstract val mcpUri: String

    /**
     * Discovery reached the end of what the server offers without finding
     * [missing] (RFC 9728, RFC 8414).
     */
    class DiscoveryFailed(
        override val mcpUri: String,
        val missing: Missing,
        detail: String,
    ) : McpAuthorizationException("Cannot authorize against $mcpUri: ${missing.label} is missing. $detail") {

        /** The piece of the server's OAuth description that was not there. */
        enum class Missing(internal val label: String) {

            /** The MCP endpoint is not an `http(s)` URL, or did not answer. */
            MCP_ENDPOINT("a reachable MCP endpoint"),

            /** Neither the 401's `resource_metadata` nor the well-known path gave protected-resource metadata. */
            PROTECTED_RESOURCE_METADATA("the protected-resource metadata (RFC 9728)"),

            /** The protected-resource metadata names no authorization server. */
            AUTHORIZATION_SERVER("an authorization server in the protected-resource metadata"),

            /** No authorization server it names publishes metadata at a well-known path. */
            AUTHORIZATION_SERVER_METADATA("the authorization server's metadata (RFC 8414)"),

            /** The authorization server's metadata has no `authorization_endpoint`. */
            AUTHORIZATION_ENDPOINT("an authorization endpoint"),

            /** The authorization server's metadata has no `token_endpoint`. */
            TOKEN_ENDPOINT("a token endpoint"),

            /** The authorization server does not offer the authorization-code grant. */
            AUTHORIZATION_CODE_GRANT("the authorization_code grant"),

            /** The authorization server does not advertise PKCE with `S256`. */
            PKCE_S256("PKCE with the S256 challenge method"),
        }
    }

    /**
     * The server published metadata this client must not act on.
     */
    class MetadataRefused(
        override val mcpUri: String,
        val reason: Reason,
        detail: String,
    ) : McpAuthorizationException("Cannot authorize against $mcpUri: $detail") {

        enum class Reason {

            /** The protected-resource metadata describes a resource other than the MCP endpoint (RFC 9728 §3.3). */
            RESOURCE_MISMATCH,

            /** The authorization server's metadata states a different issuer than the one asked (RFC 8414 §3.3). */
            ISSUER_MISMATCH,

            /** An OAuth URL is neither `https` nor `http` on a loopback host. */
            INSECURE_URL,
        }
    }

    /**
     * No client to authorize as: none was supplied, none is stored for this
     * authorization server, and the server offers no registration endpoint
     * (RFC 7591).
     */
    class ClientRegistrationUnavailable(
        override val mcpUri: String,
    ) : McpAuthorizationException(
        "Cannot authorize against $mcpUri: its authorization server offers no dynamic client registration, " +
            "and no client id was supplied. Register a client with the server and supply its id.",
    )

    /**
     * The registration endpoint did not register a client.
     *
     * @property refused True when the endpoint answered with an error, false
     *   when it gave no usable answer and another try may work.
     */
    class ClientRegistrationFailed(
        override val mcpUri: String,
        val reason: String,
        val refused: Boolean,
    ) : McpAuthorizationException("Cannot authorize against $mcpUri: client registration failed ($reason)")

    /**
     * The host's browser step did not bring a redirect back: it could not
     * start, the person closed it, or it timed out.
     */
    class BrowserStepFailed(
        override val mcpUri: String,
        cause: Throwable,
    ) : McpAuthorizationException(
        // The host's own message is left on the cause and not repeated here.
        "The authorization for $mcpUri did not complete: the browser step failed (${cause::class.simpleName})",
        cause,
    )

    /**
     * The redirect's `state` is not the one this authorization sent, so the
     * redirect belongs to some other request and is not acted on.
     */
    class StateMismatch(
        override val mcpUri: String,
    ) : McpAuthorizationException(
        "The authorization for $mcpUri was refused: the redirect's state does not match this request",
    )

    /**
     * The redirect's `iss` is missing or names another authorization server
     * (RFC 9207), so the code is not sent anywhere.
     *
     * @property expectedIssuer The issuer discovery found.
     * @property presented Whether the redirect carried an `iss` at all.
     */
    class IssuerMismatch(
        override val mcpUri: String,
        val expectedIssuer: String,
        val presented: Boolean,
    ) : McpAuthorizationException(
        "The authorization for $mcpUri was refused: the redirect " +
            (if (presented) "names a different issuer than" else "carries no issuer, and the server advertises") +
            " $expectedIssuer",
    )

    /**
     * The authorization server sent the person back with an error instead of a
     * code (RFC 6749 §4.1.2.1), e.g. `access_denied` when they declined.
     */
    class AuthorizationDenied(
        override val mcpUri: String,
        val error: String,
    ) : McpAuthorizationException("The authorization for $mcpUri was not granted ($error)")

    /** The redirect carried neither a code nor an error. */
    class NoAuthorizationCode(
        override val mcpUri: String,
    ) : McpAuthorizationException("The authorization for $mcpUri did not complete: the redirect carried no code")

    /** The token endpoint refused the code (RFC 6749 §5.2). The code is spent: authorize again. */
    class TokenRejected(
        override val mcpUri: String,
        val error: String,
    ) : McpAuthorizationException("The token endpoint for $mcpUri refused the authorization code ($error)")

    /** The token endpoint gave no usable answer. The code is single-use, so authorize again. */
    class TokenUnavailable(
        override val mcpUri: String,
        val reason: String,
    ) : McpAuthorizationException("The authorization code for $mcpUri could not be exchanged: $reason")

    /**
     * The tokens were issued and could not be stored, so they are gone with
     * this call: an authorization code cannot be redeemed twice. Fix the
     * credential store and authorize again.
     */
    class PersistFailed(
        override val mcpUri: String,
        cause: Throwable,
    ) : McpAuthorizationException(
        "The credential for $mcpUri was issued and could not be saved (${cause.message}). " +
            "Fix the credential store and authorize again.",
        cause,
    )
}
