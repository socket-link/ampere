package link.socket.ampere.mcp

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.link.LinkId

/**
 * The scope a Link's credential was granted, stored beside the credential
 * (AMPR-516).
 *
 * An authorization server may grant something other than what was asked for:
 * the server this was written against was asked for its read scope and
 * answered with the operate scope as well. So what is recorded is the answer.
 * [requested] is kept beside it so a consent surface can say that the two
 * differ rather than repeat the request.
 *
 * @property granted The scopes in force: from the token response when it
 *   stated them, else the ones requested (RFC 6749 §5.1 lets a server omit
 *   `scope` only when it granted exactly what was asked).
 * @property requested The scopes the authorization request asked for. Empty
 *   when nothing was asked for, or when the credential was seeded by hand.
 * @property statedByServer Whether [granted] is the server's own statement
 *   rather than the request assumed granted.
 */
@Serializable
data class McpGrantedScope(
    val granted: Set<String>,
    val requested: Set<String> = emptySet(),
    val statedByServer: Boolean = false,
)

/**
 * What a Link's credential lets it do and when that stops, with no token in it
 * (AMPR-516).
 *
 * This is what a host shows: "can read and operate; expires 22 December". It is
 * read off the stored credential by [McpCredential.grant] and holds nothing a
 * log may not carry, so it can travel to a settings screen, an event or a trace
 * where the credential cannot.
 *
 * @property scopes The scopes granted, or null when nobody recorded them (a
 *   credential seeded from a token response that stated none).
 * @property requestedScopes What was asked for, or null when [scopes] is.
 * @property scopesStatedByServer Whether [scopes] came from the server.
 * @property expiresAt When the access token stops working, if the issuer said.
 * @property renewable Whether a refresh can mint the next access token. When
 *   false, [expiresAt] is when the Link stops working until the person
 *   authorizes again.
 * @property revocable Whether the authorization server advertised a revocation
 *   endpoint (RFC 7009). When false, removing the credential here leaves the
 *   server-side grant in force until it expires or the person removes it in the
 *   other app, and a host must not describe it as revoked.
 */
data class McpGrant(
    val scopes: Set<String>?,
    val requestedScopes: Set<String>?,
    val scopesStatedByServer: Boolean,
    val expiresAt: Instant?,
    val renewable: Boolean,
    val revocable: Boolean,
) {

    /** Scopes the server granted that were never asked for. */
    val beyondRequest: Set<String>
        get() = scopes.orEmpty() - requestedScopes.orEmpty()

    /** Scopes that were asked for and not granted. */
    val withheld: Set<String>
        get() = if (scopes == null) emptySet() else requestedScopes.orEmpty() - scopes

    /** Whether the grant is something other than what was asked for. */
    val differsFromRequest: Boolean
        get() = beyondRequest.isNotEmpty() || withheld.isNotEmpty()

    /**
     * When the Link stops working unless the person authorizes again: the
     * expiry of a grant that cannot be renewed. Null for a renewable grant,
     * and when the issuer stated no expiry.
     */
    val stopsWorkingAt: Instant?
        get() = if (renewable) null else expiresAt
}

/** The grant behind the credential bound for [linkId] at [mcpUri], or null when nothing is bound. */
suspend fun McpCredentialBinding.grantFor(linkId: LinkId, mcpUri: String): Result<McpGrant?> =
    resolve(linkId, mcpUri).map { it?.grant }

/**
 * What became of a Link's credential when it was let go (AMPR-516).
 *
 * The stored credential is gone in every variant but [NothingBound]. What
 * differs is the grant on the authorization server, and a host has to say
 * which: a "disconnected" label over a token that still works elsewhere is a
 * false statement about the person's account.
 */
sealed interface McpRevocation {

    /** Nothing was bound for the Link at this server. */
    data object NothingBound : McpRevocation

    /**
     * The authorization server confirmed the revocation (RFC 7009) and the
     * stored credential was removed.
     */
    data object Revoked : McpRevocation

    /**
     * The stored credential was removed and the server-side grant is still in
     * force: until [remainsUntil], or until the person removes it in the app
     * that issued it.
     *
     * @property reason Why Ampere could not revoke it.
     * @property remainsUntil When the grant lapses by itself, if that is known:
     *   the expiry of a token that had no refresh token. Null when the issuer
     *   stated no expiry, and for a grant that carried a refresh token, whose
     *   lifetime the issuer never states.
     * @property detail What the revocation endpoint answered, for [Reason.REVOCATION_FAILED].
     */
    data class GrantRemains(
        val reason: Reason,
        val remainsUntil: Instant?,
        val detail: String? = null,
    ) : McpRevocation {

        enum class Reason {

            /** The authorization server advertises no revocation endpoint, so there was nothing to call. */
            NO_REVOCATION_ENDPOINT,

            /** A revocation endpoint is advertised and did not confirm the revocation. */
            REVOCATION_FAILED,
        }
    }
}
