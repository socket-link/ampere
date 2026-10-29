package link.socket.ampere.mcp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.link.LinkId

/**
 * Mints a Link's next access token from its refresh token and writes the
 * result back to the [binding] (AMPR-464).
 *
 * [McpClient] calls [refresh] in two places: on connect, when [isDue] says the
 * stored token is expired or close to it, and after a 401, once.
 *
 * ### Rotation
 *
 * An issuer may rotate the refresh token on every refresh: the answer carries
 * a new one and the one presented is spent. So the refreshed credential is
 * [bound][McpCredentialBinding.bind] — one replace of the stored credential —
 * before [refresh] returns it, and nothing ever unbinds to make room. At every
 * instant the store holds either the old credential or the new one, never
 * neither. A process killed after the endpoint answered and before the write
 * still has the old refresh token on disk, which an issuer with a replay grace
 * window (the work source's is 30 minutes) answers again with the same new
 * tokens.
 *
 * If the write fails, the refreshed credential is the only copy of the new
 * refresh token. It is held in memory, [McpCredentialException.PersistFailed]
 * is reported, and the next [refresh] writes it before doing anything else —
 * it never presents a refresh token whose successor it failed to keep.
 *
 * ### Concurrent refreshers
 *
 * Refreshes are serialized within the process, and each one re-reads the
 * binding first: a caller whose 401 raced another caller's refresh finds the
 * new token already stored and uses it rather than spending another refresh
 * token. Across processes there is no lock. A refresh refused because another
 * process already spent the token re-reads the binding and takes the rotated
 * credential that process stored; an issuer's replay window covers two
 * processes refreshing at the same moment.
 *
 * @param refreshMargin How long before [McpCredential.expiresAt] a token
 *   counts as due, so a connection is not opened on a token about to expire.
 * @param persistAttempts How many times a refreshed credential's write is
 *   tried before [McpCredentialException.PersistFailed].
 */
class McpCredentialRefresher(
    private val binding: McpCredentialBinding,
    private val tokenEndpoint: OAuthTokenEndpoint = KtorOAuthTokenEndpoint(),
    private val clock: Clock = Clock.System,
    val refreshMargin: Duration = DEFAULT_REFRESH_MARGIN,
    private val persistAttempts: Int = DEFAULT_PERSIST_ATTEMPTS,
) {

    init {
        require(persistAttempts >= 1) { "persistAttempts must be at least 1, was $persistAttempts" }
    }

    private val mutex = Mutex()

    /** Refreshed credentials whose write failed, by (Link, server): the only copy of their refresh token. */
    private val unsaved = mutableMapOf<Pair<LinkId, String>, McpCredential>()

    /** Whether [credential] should be refreshed before a connection is opened on it. */
    fun isDue(credential: McpCredential): Boolean =
        credential.isRefreshable && credential.expiresWithin(refreshMargin, clock.now())

    /**
     * The credential to use in place of [stale], refreshed and persisted if
     * that is what it takes.
     *
     * Returns the stored credential without calling the endpoint when it has
     * already moved past [stale] — another caller refreshed — and is not
     * itself due.
     *
     * @param stale The credential that was found expired, or that the server
     *   refused. Null when the connection was opened with none.
     */
    suspend fun refresh(
        linkId: LinkId,
        mcpUri: String,
        stale: McpCredential?,
    ): Result<McpCredential> = mutex.withLock {
        refreshLocked(linkId, mcpUri, stale)
    }

    private suspend fun refreshLocked(
        linkId: LinkId,
        mcpUri: String,
        stale: McpCredential?,
    ): Result<McpCredential> {
        val key = linkId to mcpUri
        unsaved[key]?.let { held ->
            persist(linkId, mcpUri, held).getOrElse { return Result.failure(it) }
            unsaved.remove(key)
        }

        var stored = read(linkId, mcpUri).getOrElse { return Result.failure(it) }
        if (supersedes(stored, stale)) return Result.success(stored)

        repeat(MAX_ENDPOINT_CALLS) {
            val presented = stored.refreshToken
            val client = stored.oauthClient
            if (presented == null || client == null) {
                return Result.failure(McpCredentialException.NotRefreshable(linkId))
            }

            when (val answer = tokenEndpoint.refresh(client, presented)) {
                is OAuthTokenResult.Issued -> {
                    val next = stored.refreshedWith(answer, clock.now())
                    persist(linkId, mcpUri, next).getOrElse { failure ->
                        unsaved[key] = next
                        return Result.failure(failure)
                    }
                    return Result.success(next)
                }

                is OAuthTokenResult.Rejected -> {
                    // Refused because another process spent it first? Then that process stored
                    // the rotated credential, and the binding has moved on from what was presented.
                    val current = read(linkId, mcpUri).getOrElse { return Result.failure(it) }
                    if (current.refreshToken == presented) {
                        return Result.failure(McpCredentialException.RefreshRejected(linkId, answer.error))
                    }
                    if (supersedes(current, stale)) return Result.success(current)
                    stored = current
                }

                is OAuthTokenResult.Unavailable ->
                    return Result.failure(McpCredentialException.RefreshUnavailable(linkId, answer.reason))
            }
        }

        return Result.failure(McpCredentialException.RefreshRejected(linkId, "rejected after the binding moved on"))
    }

    private suspend fun read(linkId: LinkId, mcpUri: String): Result<McpCredential> {
        val stored = binding.resolve(linkId, mcpUri)
            .getOrElse { return Result.failure(McpCredentialException.Unreadable(linkId, it)) }
            ?: return Result.failure(McpCredentialException.NotBound(linkId, mcpUri))
        return Result.success(stored)
    }

    /** Whether [stored] is a different, usable credential than [stale] — someone refreshed already. */
    private fun supersedes(stored: McpCredential, stale: McpCredential?): Boolean =
        stored.authToken != null &&
            stored.authToken != stale?.authToken &&
            !stored.expiresWithin(refreshMargin, clock.now())

    private suspend fun persist(linkId: LinkId, mcpUri: String, credential: McpCredential): Result<Unit> {
        var lastFailure: Throwable? = null
        repeat(persistAttempts) {
            binding.bind(linkId, mcpUri, credential)
                .onSuccess { return Result.success(Unit) }
                .onFailure { lastFailure = it }
        }
        return Result.failure(
            McpCredentialException.PersistFailed(
                linkId = linkId,
                cause = lastFailure ?: IllegalStateException("the binding refused the write"),
            ),
        )
    }

    private fun McpCredential.refreshedWith(answer: OAuthTokenResult.Issued, now: Instant): McpCredential =
        copy(
            authToken = answer.accessToken,
            // An issuer that does not rotate leaves the presented refresh token in force.
            refreshToken = answer.refreshToken ?: refreshToken,
            // An answer with no expiry gives none; keeping the old one would make the new token look spent.
            expiresAt = answer.expiresIn?.let { now + it.seconds },
        )

    companion object {

        /** A token within five minutes of expiry is refreshed before a connection is opened on it. */
        val DEFAULT_REFRESH_MARGIN: Duration = 5.minutes

        const val DEFAULT_PERSIST_ATTEMPTS: Int = 3

        /** One refresh, plus one retry with the rotated token another process stored meanwhile. */
        private const val MAX_ENDPOINT_CALLS = 2
    }
}
