package link.socket.ampere.propel

import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.plug.PlugContext
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.GateResult
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.PlugPermissionGate
import link.socket.ampere.plug.permission.PlugToolCall
import link.socket.ampere.plug.permission.UserGrants

/**
 * Single entry point for plug-sourced tool invocations during the PROPEL
 * Execute phase.
 *
 * The step looks up the tool by ID against [PlugContext.availableTools],
 * runs [PlugPermissionGate] using the manifest plus user grants supplied
 * by [userGrantProvider], and then dispatches:
 * - [McpTool]: routes through the [PlugContext]'s matching
 *   [link.socket.ampere.mcp.McpClient].
 * - [FunctionTool]: not routed here yet — native plug tool execution
 *   lands with the broader plug runtime work, so the step returns a
 *   typed [ExecuteResult.NativeToolNotSupported] rather than guessing at
 *   an `ExecutionRequest` to construct.
 *
 * Because plug-sourced MCP calls flow through this single step, the
 * permission gate cannot be bypassed by callers that need MCP access.
 */
class ExecuteStep(
    private val context: PlugContext,
    private val userGrantProvider: suspend (PlugManifest) -> UserGrants,
) {

    suspend fun execute(toolId: ToolId, arguments: JsonElement?): ExecuteResult {
        val tool = context.availableTools().firstOrNull { it.id == toolId }
            ?: return ExecuteResult.UnknownTool(toolId)

        val gateResult = PlugPermissionGate.check(
            toolCall = PlugToolCall(
                plugId = context.manifest.id,
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
        val permission: PlugPermission,
        val reason: PermissionDeniedReason,
    ) : ExecuteResult

    data class NativeToolNotSupported(val toolId: ToolId) : ExecuteResult

    data class Failure(val message: String) : ExecuteResult
}

enum class PermissionDeniedReason {
    MISSING_GRANT,
    REVOKED_GRANT,
}
