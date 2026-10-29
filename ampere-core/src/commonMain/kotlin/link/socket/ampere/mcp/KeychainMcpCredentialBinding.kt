package link.socket.ampere.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.LinkId

/**
 * A platform keychain, reduced to what a credential binding needs: one secret
 * string per (service, account) pair, replaced whole on every write.
 *
 * [write] must replace an existing item in a single step, so a reader sees the
 * old secret or the new one and never neither — [McpCredentialRefresher]'s
 * rotation safety rests on it. Failures must not quote the secret.
 */
interface SecretStore {

    /** The secret stored under ([service], [account]), or null when there is none. */
    suspend fun read(service: String, account: String): Result<String?>

    /** Store [secret] under ([service], [account]), replacing any existing one in one step. */
    suspend fun write(service: String, account: String, secret: String): Result<Unit>

    /** Remove the secret under ([service], [account]). Removing one that is not there succeeds. */
    suspend fun delete(service: String, account: String): Result<Unit>
}

/**
 * The persistent [McpCredentialBinding]: each credential is one item in the
 * platform keychain (AMPR-464).
 *
 * The item is found by the Link's [CredentialRef] alias, honoured rather than
 * stored: the keychain *service* is [CredentialRef.aliasFor] the Link
 * (`ampere.link:<link id>`) and the *account* is the MCP server URI. Distinct
 * Links get distinct services, and finding a Link's credentials needs no table.
 * The item holds the [McpCredential] as JSON.
 *
 * ### Storage decision
 *
 * [CredentialRef] already says *platform keychain*, so this binding asks the
 * platform for one through [SecretStore]; on the JVM that is `MacOsKeychain`,
 * which is macOS-only for v1 (Linux and Windows hosts get a failure that says
 * so, and keep the in-memory binding plus an explicit token). Rejected:
 *
 * - **A row in the SQLDelight database beside `Links`.** `~/.ampere/ampere.db`
 *   is an unencrypted file that also holds the event log the traces are built
 *   from; the Link invariant exists to keep secrets out of exactly that file.
 * - **A credentials file under `~/.ampere/`**, even mode 0600. It is a new
 *   storage class to secure, back up and rotate, which the [CredentialRef]
 *   contract was written to avoid, and it sits one `cp -r` from a repository.
 * - **`java.security.KeyStore` ("KeychainStore")**: Apple's JDK provider holds
 *   keys and certificates, not generic passwords.
 * - **A native Security.framework binding (JNA, or the Swift helper)**: a new
 *   dependency or process for three calls `/usr/bin/security` already makes.
 *
 * [InMemoryMcpCredentialBinding] stays the twin for tests.
 *
 * ### Nothing leaks
 *
 * A failure to decode an item says so without quoting it — the serializer's
 * own message would include the JSON, token and all — and nothing here logs.
 */
class KeychainMcpCredentialBinding(
    private val store: SecretStore,
) : McpCredentialBinding {

    override suspend fun bind(
        linkId: LinkId,
        mcpUri: String,
        credential: McpCredential,
    ): Result<Unit> {
        val encoded = json.encodeToString(McpCredential.serializer(), credential)
        return store.write(serviceFor(linkId), mcpUri, encoded)
    }

    override suspend fun resolve(
        linkId: LinkId,
        mcpUri: String,
    ): Result<McpCredential?> {
        val stored = store.read(serviceFor(linkId), mcpUri).getOrElse { return Result.failure(it) }
            ?: return Result.success(null)
        return try {
            Result.success(json.decodeFromString(McpCredential.serializer(), stored))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "The keychain item for Link ${linkId.value} at $mcpUri is not a credential this " +
                        "build can read (${e::class.simpleName}); seed it again",
                ),
            )
        }
    }

    override suspend fun unbind(
        linkId: LinkId,
        mcpUri: String,
    ): Result<Unit> = store.delete(serviceFor(linkId), mcpUri)

    private fun serviceFor(linkId: LinkId): String = CredentialRef.aliasFor(linkId)

    private companion object {

        /** Unknown keys are skipped, so an item a newer build wrote still decodes in an older one. */
        val json = Json { ignoreUnknownKeys = true }
    }
}
