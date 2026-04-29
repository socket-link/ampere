package link.socket.ampere.propel

import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.plugin.PluginContext
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.GateResult
import link.socket.ampere.plugin.permission.PluginPermission
import link.socket.ampere.plugin.permission.PluginPermissionGate
import link.socket.ampere.plugin.permission.PluginToolCall
import link.socket.ampere.plugin.permission.UserGrants

/**
 * Single entry point for plugin-sourced tool invocations during the PROPEL
 * Execute phase.
 *
 * The step looks up the tool by ID against [PluginContext.availableTools],
 * runs [PluginPermissionGate] using the manifest plus user grants supplied
 * by [userGrantProvider], and then dispatches:
 * - [McpTool]: routes through the [PluginContext]'s matching
 *   [link.socket.ampere.mcp.McpClient].
 * - [FunctionTool]: not routed here yet — native plugin tool execution
 *   lands with the broader plugin runtime work, so the step returns a
 *   typed [ExecuteResult.NativeToolNotSupported] rather than guessing at
 *   an `ExecutionRequest` to construct.
 *
 * Because plugin-sourced MCP calls flow through this single step, the
 * permission gate cannot be bypassed by callers that need MCP access.
 */
class ExecuteStep(
    private val context: PluginContext,
    private val userGrantProvider: suspend (PluginManifest) -> UserGrants,
) {

    suspend fun execute(toolId: ToolId, arguments: JsonElement?): ExecuteResult {
        val tool = context.availableTools().firstOrNull { it.id == toolId }
            ?: return ExecuteResult.UnknownTool(toolId)

        val gateResult = PluginPermissionGate.check(
            toolCall = PluginToolCall(
                pluginId = context.manifest.id,
                toolId = tool.id,
            ),
            manifest = context.manifest,
            userGrants = userGrantProvider(context.manifest),
        )

        when (gateResult) {
            is GateResult.Allow -> Unit
            is GateResult.DenyMissing -> return ExecuteResult.PermissionDenied(
                permission = gateResult.permission,
                reason = PermissionDeniedReason.MISSING_GRANT,
            )
            is GateResult.DenyRevoked -> return ExecuteResult.PermissionDenied(
                permission = gateResult.permission,
                reason = PermissionDeniedReason.REVOKED_GRANT,
            )
        }

        return when (tool) {
            is McpTool -> dispatchMcp(tool, arguments)
            is FunctionTool<*> -> ExecuteResult.NativeToolNotSupported(tool.id)
        }
    }

    private suspend fun dispatchMcp(tool: McpTool, arguments: JsonElement?): ExecuteResult {
        val client = context.mcpClientFor(tool)
            ?: return ExecuteResult.Failure(
                "No MCP client registered for tool '${tool.id}' (server '${tool.serverId}')",
            )

        return client.callTool(tool.remoteToolName, arguments).fold(
            onSuccess = { ExecuteResult.Success(it) },
            onFailure = { ExecuteResult.Failure(it.message ?: it::class.simpleName.orEmpty()) },
        )
    }
}

sealed interface ExecuteResult {
    data class Success(val result: ToolCallResult) : ExecuteResult

    data class UnknownTool(val toolId: ToolId) : ExecuteResult

    data class PermissionDenied(
        val permission: PluginPermission,
        val reason: PermissionDeniedReason,
    ) : ExecuteResult

    data class NativeToolNotSupported(val toolId: ToolId) : ExecuteResult

    data class Failure(val message: String) : ExecuteResult
}

enum class PermissionDeniedReason {
    MISSING_GRANT,
    REVOKED_GRANT,
}
