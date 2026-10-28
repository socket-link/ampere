package link.socket.ampere.agents.execution.executor

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport

/** The `Executor.id` the OpenCode harness is recorded under (AMPR-444 D8). */
const val OPENCODE_EXECUTOR_ID: ExecutorId = "opencode"

/** The variable OpenCode reads one JSON config object from, layered over its own files. */
internal const val OPENCODE_CONFIG_CONTENT = "OPENCODE_CONFIG_CONTENT"

/**
 * The config [openCode] spawns OpenCode with: ask before every edit and every shell command
 * (AMPR-504). OpenCode's default asks for neither — it writes and runs from its own process and
 * only reports the tool call — so without this the fence and `ShellAccess.Deny` are never
 * consulted. `bash` stays `ask` under `ShellAccess.Allow` too: the fence answers `allow_once`,
 * and every command stays on the record.
 */
internal val OPENCODE_ASK_PERMISSIONS: JsonObject = buildJsonObject {
    put(
        "permission",
        buildJsonObject {
            put("edit", "ask")
            put("bash", "ask")
        },
    )
}

/**
 * The first harness: OpenCode as an ACP agent, `opencode acp` on stdio (AMPR-493, AMPR-444 D8).
 *
 * Spawns [binary] with `acp` through a [StdioAcpTransport] on first use, roots each session
 * at the step's workspace, and audits each turn with a [GitWorkspaceObserver] — the layer
 * that holds, because OpenCode writes edits to disk from its own process whether or not the
 * client advertised `fs.writeTextFile` (anomalyco/opencode#38196): the client's
 * `fs/write_text_file` and `fs/read_text_file` are not called in 2.2.0, even with an edit
 * allowed (AMPR-498). Permission options are matched by `kind`, never by
 * OpenCode's own option ids.
 *
 * OpenCode asks no permission by default, so the agent is spawned with [OPENCODE_CONFIG_CONTENT]
 * carrying [OPENCODE_ASK_PERMISSIONS] — every edit and every shell command then arrives as a
 * `session/request_permission` the fence answers before anything happens — and with
 * `OPENCODE_DISABLE_AUTOUPDATE=1`, so a turn never waits on a self-update.
 *
 * Bind it behind the tool a planner nominates:
 * ```kotlin
 * val harness = AcpCodeExecutor.openCode(workspace = ExecutionWorkspace("/path/to/checkout"))
 * val executor = RoutedExecutor(
 *     default = FunctionExecutor.create(),
 *     bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness),
 * )
 * rosterRunHost(createEventApi = …, agentScope = …, executor = executor)
 * ```
 *
 * @param binary The OpenCode executable: a name on the PATH or an absolute path.
 * @param workspace The checkout this executor works in when a request names none — every
 *   hosted-run step, since a hosted seat carries no workspace.
 * @param policy Shell access, cancel grace and timeouts.
 * @param environment Variables added to the agent's environment (an API key, say). An
 *   [OPENCODE_CONFIG_CONTENT] entry must be a JSON object; it is merged over
 *   [OPENCODE_ASK_PERMISSIONS], the caller winning on a colliding key (`model`, say, or a
 *   `permission` entry), objects merged key by key.
 * @param extraArguments Arguments after `acp`.
 * @throws IllegalArgumentException when [environment]'s [OPENCODE_CONFIG_CONTENT] is not a JSON object.
 */
fun AcpCodeExecutor.Companion.openCode(
    binary: String = "opencode",
    workspace: ExecutionWorkspace? = null,
    policy: HarnessPolicy = HarnessPolicy(),
    environment: Map<String, String> = emptyMap(),
    extraArguments: List<String> = emptyList(),
): AcpCodeExecutor {
    val spawnEnvironment = openCodeEnvironment(environment)
    return AcpCodeExecutor(
        id = OPENCODE_EXECUTOR_ID,
        displayName = "OpenCode (ACP)",
        transportFactory = {
            StdioAcpTransport(
                command = listOf(binary, "acp") + extraArguments,
                directory = workspace?.let { File(it.baseDirectory) },
                environment = spawnEnvironment,
            )
        },
        observerFactory = { target -> GitWorkspaceObserver(File(target.baseDirectory)) },
        policy = policy,
        workspace = workspace,
    )
}

/**
 * [environment] with OpenCode's ask-mode config and auto-update switched off (AMPR-504). A
 * caller's own [OPENCODE_CONFIG_CONTENT] is merged over [OPENCODE_ASK_PERMISSIONS], so setting
 * `model` keeps the asks; a caller's `OPENCODE_DISABLE_AUTOUPDATE` wins.
 *
 * @throws IllegalArgumentException when the caller's [OPENCODE_CONFIG_CONTENT] is not a JSON object.
 */
internal fun openCodeEnvironment(environment: Map<String, String>): Map<String, String> {
    val callerConfig = environment[OPENCODE_CONFIG_CONTENT]?.let { raw ->
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        require(parsed is JsonObject) { "$OPENCODE_CONFIG_CONTENT must be one JSON object, was: $raw" }
        parsed
    }
    val config = callerConfig?.let { mergeJsonObjects(OPENCODE_ASK_PERMISSIONS, it) } ?: OPENCODE_ASK_PERMISSIONS
    return mapOf("OPENCODE_DISABLE_AUTOUPDATE" to "1") + environment + (OPENCODE_CONFIG_CONTENT to config.toString())
}

/** [base] with [override] laid over it: objects merge key by key, anything else is replaced. */
private fun mergeJsonObjects(base: JsonObject, override: JsonObject): JsonObject =
    JsonObject(
        base + override.mapValues { (key, value) ->
            val under: JsonElement? = base[key]
            if (under is JsonObject && value is JsonObject) mergeJsonObjects(under, value) else value
        },
    )
