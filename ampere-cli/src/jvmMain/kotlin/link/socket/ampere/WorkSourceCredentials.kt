package link.socket.ampere

import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.mcp.KeychainMcpCredentialBinding
import link.socket.ampere.mcp.KtorOAuthTokenEndpoint
import link.socket.ampere.mcp.MacOsKeychain
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpCredentialBinding
import link.socket.ampere.mcp.McpCredentialRefresher
import link.socket.ampere.mcp.OAuthTokenEndpoint
import link.socket.ampere.work.linear.LinearWorkSource

/**
 * Where a command that talks to the work source gets its credential (AMPR-464):
 * `ampere reconcile` today, the dispatch loop (AMPR-308) next.
 *
 * - **By default, the keychain.** The credential `ampere credential seed` stored
 *   for the work-source Link, with a [McpCredentialRefresher] so a short-lived
 *   OAuth token is refreshed on connect and after a 401, and every rotated
 *   refresh token is written back before the old one is let go.
 * - **`$AMPERE_WORK_SOURCE_TOKEN`, when set, overrides it** for one run: a static
 *   bearer, bound in memory, never refreshed and never stored. It is for a probe
 *   with a hand-minted token, or a host without a supported keychain. A token
 *   pasted into it daily is the shape this replaces, not a fallback for it.
 */
class WorkSourceCredentials(
    private val environment: (String) -> String? = System::getenv,
    private val keychain: () -> McpCredentialBinding = { keychainBinding() },
    private val tokenEndpoint: () -> OAuthTokenEndpoint = { KtorOAuthTokenEndpoint() },
) {

    /** Where [Opened.binding] came from. */
    enum class Origin { KEYCHAIN, ENVIRONMENT_OVERRIDE }

    /**
     * A credential ready for [link.socket.ampere.plug.PlugContext.create]: the binding
     * holding it, and the refresher to pass beside it (null for the override, which has
     * nothing to refresh with).
     */
    class Opened(
        val binding: McpCredentialBinding,
        val refresher: McpCredentialRefresher?,
        val origin: Origin,
    )

    /**
     * The credential for [linkId] at the work source [uri]. Fails, with the command
     * that fixes it, when neither the override nor a seeded keychain item exists.
     */
    suspend fun open(linkId: LinkId, uri: String): Result<Opened> {
        val override = environment(TOKEN_ENV)?.takeIf { it.isNotBlank() }
        if (override != null) {
            val binding = InMemoryMcpCredentialBinding()
            binding.bind(linkId, uri, McpCredential(authToken = override)).getOrElse { return Result.failure(it) }
            return Result.success(Opened(binding, refresher = null, origin = Origin.ENVIRONMENT_OVERRIDE))
        }

        val binding = keychain()
        val alias = CredentialRef.aliasFor(linkId)
        val stored = binding.resolve(linkId, uri).getOrElse { cause ->
            return Result.failure(
                WorkSourceCredentialException(
                    "Could not read the work-source credential from the keychain (service $alias): ${cause.message}. " +
                        "Set \$$TOKEN_ENV to use a token for this run instead.",
                    cause,
                ),
            )
        }
        if (stored == null) {
            return Result.failure(
                WorkSourceCredentialException(
                    "No work-source credential is seeded for $uri (keychain service $alias, account $uri). " +
                        "Seed one with `ampere credential seed --work-source $uri …` (see ampere-cli/README.md), " +
                        "or set \$$TOKEN_ENV to use a token for this run.",
                ),
            )
        }

        return Result.success(
            Opened(
                binding = binding,
                refresher = McpCredentialRefresher(binding, tokenEndpoint()),
                origin = Origin.KEYCHAIN,
            ),
        )
    }

    companion object {

        /** The explicit override: a static bearer for one run. */
        const val TOKEN_ENV = "AMPERE_WORK_SOURCE_TOKEN"

        /**
         * The work-source Link's id: the one minted for the manifest's work-source
         * requirement, `mcp:work-source`. Every command derives the same id, so they
         * all find the same keychain item under [CredentialRef.aliasFor] it.
         */
        fun linkIdFor(uri: String): LinkId =
            LinkId.forRequirement(
                LinearWorkSource.manifest(uri).requiredLinks.single { it.name == LinearWorkSource.DEPENDENCY_NAME },
            )

        /** The persistent binding: the macOS keychain, through `/usr/bin/security`. */
        fun keychainBinding(): McpCredentialBinding = KeychainMcpCredentialBinding(MacOsKeychain())
    }
}

/** A work-source credential is missing or unreadable. The message says what to run. */
class WorkSourceCredentialException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
