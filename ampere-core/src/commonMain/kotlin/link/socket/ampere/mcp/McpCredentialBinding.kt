package link.socket.ampere.mcp

import kotlin.jvm.JvmInline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Identifier for a Link — the user-bound authorization scope under which
 * an MCP credential is stored.
 *
 * The Link concept is upcoming work; modeling it as a value class lets the
 * eventual store interface match without a churn cycle.
 */
@JvmInline
@Serializable
value class LinkId(val value: String)

/**
 * Credential material a plugin's [McpClient] surfaces into its connection
 * layer when calling an MCP server.
 *
 * Today only [authToken] is captured; future fields (refresh token, OAuth
 * metadata) can be added without breaking the binding contract.
 */
@Serializable
data class McpCredential(
    val authToken: String? = null,
)

/**
 * Storage contract for credentials a plugin uses to talk to an MCP server.
 *
 * Implementations are scoped per Link so credentials revocations follow the
 * Link lifecycle. The interface intentionally avoids leaking implementation
 * concerns (transactionality, persistence, encryption) so the upcoming
 * Link-backed store can drop in.
 */
interface McpCredentialBinding {
    suspend fun bind(linkId: LinkId, mcpUri: String, credential: McpCredential): Result<Unit>

    suspend fun resolve(linkId: LinkId, mcpUri: String): Result<McpCredential?>

    suspend fun unbind(linkId: LinkId, mcpUri: String): Result<Unit>
}

/**
 * In-memory [McpCredentialBinding] suitable for tests and single-process
 * environments. The persistent Link-backed implementation lands separately.
 */
class InMemoryMcpCredentialBinding : McpCredentialBinding {

    private val mutex = Mutex()
    private val store = mutableMapOf<Pair<LinkId, String>, McpCredential>()

    override suspend fun bind(
        linkId: LinkId,
        mcpUri: String,
        credential: McpCredential,
    ): Result<Unit> = mutex.withLock {
        store[linkId to mcpUri] = credential
        Result.success(Unit)
    }

    override suspend fun resolve(
        linkId: LinkId,
        mcpUri: String,
    ): Result<McpCredential?> = mutex.withLock {
        Result.success(store[linkId to mcpUri])
    }

    override suspend fun unbind(
        linkId: LinkId,
        mcpUri: String,
    ): Result<Unit> = mutex.withLock {
        store.remove(linkId to mcpUri)
        Result.success(Unit)
    }
}
