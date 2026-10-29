package link.socket.ampere.agents.execution.executor

import java.io.File
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport

/** The `Executor.id` the Claude Code harness is recorded under (AMPR-499). */
const val CLAUDE_CODE_EXECUTOR_ID: ExecutorId = "claude-code"

/**
 * How [claudeCode] launches the agent when the caller names no command: Zed's ACP adapter for
 * Claude Code, run through `npx`. Claude Code does not speak ACP itself; the adapter wraps the
 * Claude Code SDK as an ACP agent on stdio.
 */
internal val CLAUDE_CODE_ACP_COMMAND: List<String> = listOf("npx", "@zed-industries/claude-code-acp")

/**
 * The second harness: Claude Code as an ACP agent, through Zed's adapter on stdio (AMPR-499,
 * AMPR-444).
 *
 * The same [AcpCodeExecutor] as [openCode], with its own id and command line: [command] is
 * spawned through a [StdioAcpTransport] on first use, each session is rooted at the step's
 * workspace, and each turn is audited with a [GitWorkspaceObserver]. The audit is the layer
 * that holds whatever the adapter does with the client's `fs/*` methods and its permission
 * requests; permission options are matched by `kind`, never by the adapter's option ids.
 *
 * `npx` is a wrapper that starts the adapter as its child, and the adapter starts Claude Code
 * in turn. All of them stay in the group [StdioAcpTransport] spawns through `ProcessGroups`,
 * so [AcpCodeExecutor.close] ends the adapter, not only the wrapper.
 *
 * Bind it behind the tool a planner nominates:
 * ```kotlin
 * val harness = AcpCodeExecutor.claudeCode(environment = mapOf("ANTHROPIC_API_KEY" to key))
 * val executor = RoutedExecutor(
 *     default = FunctionExecutor.create(),
 *     bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness),
 * )
 * rosterRunHost(createEventApi = …, agentScope = …, executor = executor)
 * ```
 *
 * @param command The adapter's command line, [CLAUDE_CODE_ACP_COMMAND] by default; a binary on
 *   the PATH or an absolute path, then its arguments. Never put a key here: arguments are
 *   visible to anyone who can list processes.
 * @param workspace The checkout this executor works in when a request names none.
 * @param policy Shell access, cancel grace and timeouts.
 * @param environment Variables added to the agent's environment — the consumer's
 *   `ANTHROPIC_API_KEY`, say. This is the only route a key takes to the agent, and it is never
 *   logged.
 * @throws IllegalArgumentException when [command] is empty.
 */
fun AcpCodeExecutor.Companion.claudeCode(
    command: List<String> = CLAUDE_CODE_ACP_COMMAND,
    workspace: ExecutionWorkspace? = null,
    policy: HarnessPolicy = HarnessPolicy(),
    environment: Map<String, String> = emptyMap(),
): AcpCodeExecutor {
    require(command.isNotEmpty()) { "claudeCode needs a command to launch the ACP adapter" }
    return AcpCodeExecutor(
        id = CLAUDE_CODE_EXECUTOR_ID,
        displayName = "Claude Code (ACP)",
        transportFactory = {
            StdioAcpTransport(
                command = command,
                directory = workspace?.let { File(it.baseDirectory) },
                environment = environment,
            )
        },
        observerFactory = { target -> GitWorkspaceObserver(File(target.baseDirectory)) },
        policy = policy,
        workspace = workspace,
    )
}
