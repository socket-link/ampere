package link.socket.ampere.plug

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.mcp.LinkId
import link.socket.ampere.mcp.McpClient
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpCredentialBinding
import link.socket.ampere.mcp.defaultHttpConnection

/**
 * Runtime context for an active plug instance.
 *
 * Bundles a validated [PlugManifest] together with the plug's native
 * tools and the [McpClient] instances opened for each declared
 * [McpServerDependency]. The MCP tools discovered from each server are
 * exposed through [availableTools] alongside any native tools, and each
 * carries the originating manifest so [PlugPermissionGate][link.socket.ampere.plug.permission.PlugPermissionGate]
 * still gates dispatch.
 *
 * Construction goes through [create]. It validates the manifest, opens an
 * [McpClient] per dependency, runs the handshake, lists tools, and wraps
 * each descriptor as an [McpTool]. Server failures are surfaced per server
 * (mirroring the existing [link.socket.ampere.agents.tools.mcp.McpServerManager]
 * resilience pattern) so one bad server doesn't kill the plug.
 *
 * Tools are dispatched through
 * [link.socket.ampere.propel.ExecuteStep], which resolves the right
 * [McpClient] via [mcpClientFor].
 */
class PlugContext private constructor(
    val manifest: PlugManifest,
    private val nativeTools: List<Tool<*>>,
    private val mcpClientsByUri: Map<String, McpClient>,
    private val mcpToolsByServerUri: Map<String, List<McpTool>>,
    val serverFailures: List<PlugContextServerFailure>,
) {

    fun availableTools(): List<Tool<*>> =
        nativeTools + mcpToolsByServerUri.values.flatten()

    /**
     * Looks up the [McpClient] responsible for a tool's originating server.
     *
     * Returns null for tools without a matching server (e.g., a stale
     * [McpTool] referencing a server that failed to come up).
     */
    fun mcpClientFor(tool: McpTool): McpClient? =
        mcpClientsByUri[tool.serverId]

    suspend fun close(): Result<Unit> {
        val errors = mutableListOf<Throwable>()
        mcpClientsByUri.values.forEach { client ->
            client.close().onFailure { errors += it }
        }
        return if (errors.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(errors.first())
        }
    }

    companion object {
        suspend fun create(
            manifest: PlugManifest,
            credentialBinding: McpCredentialBinding,
            linkId: LinkId,
            nativeTools: List<Tool<*>> = emptyList(),
            connectionFactory: (McpServerDependency, McpCredential?) -> McpServerConnection =
                ::defaultHttpConnection,
        ): Result<PlugContext> {
            val validation = PlugManifestValidator.validate(manifest)
            if (validation is ManifestValidationResult.Invalid) {
                return Result.failure(
                    PlugManifestValidationException(validation.reasons),
                )
            }

            val clientsByUri = mutableMapOf<String, McpClient>()
            val toolsByUri = mutableMapOf<String, List<McpTool>>()
            val failures = mutableListOf<PlugContextServerFailure>()

            manifest.mcpServers.forEach { dependency ->
                val client = McpClient(
                    dependency = dependency,
                    credentialBinding = credentialBinding,
                    linkId = linkId,
                    connectionFactory = connectionFactory,
                )

                val connectResult = client.connect()
                if (connectResult.isFailure) {
                    failures += PlugContextServerFailure(
                        dependency = dependency,
                        cause = connectResult.exceptionOrNull(),
                    )
                    client.close()
                    return@forEach
                }

                val toolsResult = client.listTools()
                val descriptors = toolsResult.getOrElse { error ->
                    failures += PlugContextServerFailure(
                        dependency = dependency,
                        cause = error,
                    )
                    client.close()
                    return@forEach
                }

                clientsByUri[dependency.uri] = client
                toolsByUri[dependency.uri] = descriptors.map { descriptor ->
                    McpTool(
                        id = "${dependency.name}:${descriptor.name}",
                        name = descriptor.name,
                        description = descriptor.description,
                        requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
                        plugManifest = manifest,
                        serverId = dependency.uri,
                        remoteToolName = descriptor.name,
                        inputSchema = descriptor.inputSchema,
                    )
                }
            }

            return Result.success(
                PlugContext(
                    manifest = manifest,
                    nativeTools = nativeTools,
                    mcpClientsByUri = clientsByUri,
                    mcpToolsByServerUri = toolsByUri,
                    serverFailures = failures,
                ),
            )
        }
    }
}

/**
 * Captures a per-server failure encountered during [PlugContext.create].
 *
 * Surfaced rather than thrown so a single misbehaving MCP server doesn't
 * fail the rest of the plug's tools.
 */
data class PlugContextServerFailure(
    val dependency: McpServerDependency,
    val cause: Throwable?,
)

/**
 * Thrown when a manifest fails validation during [PlugContext.create].
 */
class PlugManifestValidationException(
    val reasons: List<ManifestValidationReason>,
) : Exception("Plug manifest validation failed: $reasons")
