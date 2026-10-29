package link.socket.ampere.mcp

import link.socket.ampere.agents.tools.mcp.connection.McpUnauthorizedException
import link.socket.ampere.link.LinkId

/**
 * Why a Link's MCP credential could not be used, refreshed or kept (AMPR-464).
 *
 * Each variant says what the operator has to do, because the answers differ:
 * a [RefreshRejected] credential has to be seeded again, an
 * [RefreshUnavailable] one only waits, and a [PersistFailed] one is a race
 * against the issuer's replay window. No message carries credential material.
 */
sealed class McpCredentialException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    abstract val linkId: LinkId

    /** Nothing is bound for the Link at this MCP server. */
    class NotBound(
        override val linkId: LinkId,
        val mcpUri: String,
    ) : McpCredentialException("No credential is bound for Link ${linkId.value} at $mcpUri")

    /** The binding could not be read. */
    class Unreadable(
        override val linkId: LinkId,
        cause: Throwable,
    ) : McpCredentialException("The credential for Link ${linkId.value} could not be read: ${cause.message}", cause)

    /** It needs refreshing and has no refresh token, or no OAuth client to present one with. */
    class NotRefreshable(
        override val linkId: LinkId,
    ) : McpCredentialException(
        "The credential for Link ${linkId.value} needs refreshing and cannot be: it has no refresh token " +
            "or no OAuth client. Seed it again.",
    )

    /** The token endpoint refused the refresh token. Only seeding the credential again helps. */
    class RefreshRejected(
        override val linkId: LinkId,
        val error: String,
    ) : McpCredentialException(
        "The token endpoint refused the refresh token for Link ${linkId.value} ($error). Seed the credential again.",
    )

    /** The token endpoint gave no usable answer. Transient: the same refresh token is still good. */
    class RefreshUnavailable(
        override val linkId: LinkId,
        val reason: String,
    ) : McpCredentialException("The credential for Link ${linkId.value} could not be refreshed: $reason")

    /**
     * A refresh succeeded and its result could not be written back.
     *
     * The issuer may already have spent the stored refresh token, so the new
     * one exists only in this process. It is held there and written before the
     * next refresh is attempted; until then this process refuses to spend it.
     * An issuer with a replay grace window still honours the stored token for
     * the length of that window.
     */
    class PersistFailed(
        override val linkId: LinkId,
        cause: Throwable,
    ) : McpCredentialException(
        "The refreshed credential for Link ${linkId.value} could not be saved (${cause.message}). " +
            "It is held in memory until a save succeeds; fix the credential store before this process exits.",
        cause,
    )

    /**
     * The MCP server refused the credential with a 401 and a refresh did not
     * change that: the refreshed credential was refused too, or there was no
     * way to refresh it.
     */
    class Rejected(
        override val linkId: LinkId,
        val refreshed: Boolean,
        cause: McpUnauthorizedException,
    ) : McpCredentialException(
        if (refreshed) {
            "The MCP server refused the credential for Link ${linkId.value} again after it was refreshed"
        } else {
            "The MCP server refused the credential for Link ${linkId.value}, and this client has no way to refresh it"
        },
        cause,
    )
}
