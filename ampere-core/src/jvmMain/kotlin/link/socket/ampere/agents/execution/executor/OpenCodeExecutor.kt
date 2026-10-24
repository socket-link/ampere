package link.socket.ampere.agents.execution.executor

import java.io.File
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport

/** The `Executor.id` the OpenCode harness is recorded under (AMPR-444 D8). */
const val OPENCODE_EXECUTOR_ID: ExecutorId = "opencode"

/**
 * The first harness: OpenCode as an ACP agent, `opencode acp` on stdio (AMPR-493, AMPR-444 D8).
 *
 * Spawns [binary] with `acp` through a [StdioAcpTransport] on first use, roots each session
 * at the step's workspace, and audits each turn with a [GitWorkspaceObserver] — the layer
 * that holds, because OpenCode writes edits to disk from its own process whether or not the
 * client advertised `fs.writeTextFile`. Permission options are matched by `kind`, never by
 * OpenCode's own option ids.
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
 * @param environment Variables added to the agent's environment (an API key, say).
 * @param extraArguments Arguments after `acp`.
 */
fun AcpCodeExecutor.Companion.openCode(
    binary: String = "opencode",
    workspace: ExecutionWorkspace? = null,
    policy: HarnessPolicy = HarnessPolicy(),
    environment: Map<String, String> = emptyMap(),
    extraArguments: List<String> = emptyList(),
): AcpCodeExecutor = AcpCodeExecutor(
    id = OPENCODE_EXECUTOR_ID,
    displayName = "OpenCode (ACP)",
    transportFactory = {
        StdioAcpTransport(
            command = listOf(binary, "acp") + extraArguments,
            directory = workspace?.let { File(it.baseDirectory) },
            environment = environment,
        )
    },
    observerFactory = { target -> GitWorkspaceObserver(File(target.baseDirectory)) },
    policy = policy,
    workspace = workspace,
)
