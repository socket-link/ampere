package link.socket.ampere.plugin

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
 * Runtime context for an active plugin instance.
 *
 * Bundles a validated [PluginManifest] together with the plugin's native
 * tools and the [McpClient] instances opened for each declared
 * [McpServerDependency]. The MCP tools discovered from each server are
 * exposed through [availableTools] alongside any native tools, and each
 * carries the originating manifest so [PluginPermissionGate][link.socket.ampere.plugin.permission.PluginPermissionGate]
 * still gates dispatch.
 *
 * Construction goes through [create]. It validates the manifest, opens an
 * [McpClient] per dependency, runs the handshake, lists tools, and wraps
 * each descriptor as an [McpTool]. Server failures are surfaced per server
 * (mirroring the existing [link.socket.ampere.agents.tools.mcp.McpServerManager]
 * resilience pattern) so one bad server doesn't kill the plugin.
 *
 * Tools are dispatched through
 * [link.socket.ampere.propel.ExecuteStep], which resolves the right
 * [McpClient] via [mcpClientFor].
 */
class PluginContext private constructor(
    val manifest: PluginManifest,
    private val nativeTools: List<Tool<*>>,
    private val mcpClientsByUri: Map<String, McpClient>,
    private val mcpToolsByServerUri: Map<String, List<McpTool>>,
    val serverFailures: List<PluginContextServerFailure>,
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
            manifest: PluginManifest,
            credentialBinding: McpCredentialBinding,
            linkId: LinkId,
            nativeTools: List<Tool<*>> = emptyList(),
            connectionFactory: (McpServerDependency, McpCredential?) -> McpServerConnection =
                ::defaultHttpConnection,
        ): Result<PluginContext> {
            val validation = PluginManifestValidator.validate(manifest)
            if (validation is ManifestValidationResult.Invalid) {
                return Result.failure(
                    PluginManifestValidationException(validation.reasons),
                )
            }

            val clientsByUri = mutableMapOf<String, McpClient>()
            val toolsByUri = mutableMapOf<String, List<McpTool>>()
            val failures = mutableListOf<PluginContextServerFailure>()

            manifest.mcpServers.forEach { dependency ->
                val client = McpClient(
                    dependency = dependency,
                    credentialBinding = credentialBinding,
                    linkId = linkId,
                    connectionFactory = connectionFactory,
                )

                val connectResult = client.connect()
                if (connectResult.isFailure) {
                    failures += PluginContextServerFailure(
                        dependency = dependency,
                        cause = connectResult.exceptionOrNull(),
                    )
                    client.close()
                    return@forEach
                }

                val toolsResult = client.listTools()
                val descriptors = toolsResult.getOrElse { error ->
                    failures += PluginContextServerFailure(
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
                        pluginManifest = manifest,
                        serverId = dependency.uri,
                        remoteToolName = descriptor.name,
                        inputSchema = descriptor.inputSchema,
                    )
                }
            }

            return Result.success(
                PluginContext(
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
 * Captures a per-server failure encountered during [PluginContext.create].
 *
 * Surfaced rather than thrown so a single misbehaving MCP server doesn't
 * fail the rest of the plugin's tools.
 */
data class PluginContextServerFailure(
    val dependency: McpServerDependency,
    val cause: Throwable?,
)

/**
 * Thrown when a manifest fails validation during [PluginContext.create].
 */
class PluginManifestValidationException(
    val reasons: List<ManifestValidationReason>,
) : Exception("Plugin manifest validation failed: $reasons")
