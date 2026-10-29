package link.socket.ampere.agents.execution.executor

import java.io.File
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport

/** The `Executor.id` the Junie harness is recorded under (AMPR-500). */
const val JUNIE_EXECUTOR_ID: ExecutorId = "junie"

/**
 * The arguments that put Junie CLI in ACP mode, exactly as the ACP registry's `junie` entry
 * launches it (agentclientprotocol registry, Junie 3893.6, checked 2026-10-29).
 */
internal val JUNIE_ACP_ARGUMENTS: List<String> = listOf("--acp=true")

/**
 * The third harness: JetBrains' Junie CLI as an ACP agent, `junie --acp=true` on stdio (AMPR-500).
 *
 * Spawns [binary] with [JUNIE_ACP_ARGUMENTS] through a [StdioAcpTransport] on first use, roots
 * each session at the step's workspace, and audits each turn with a [GitWorkspaceObserver].
 * Nothing about the executor is Junie's own: the id, the display name and the command line are
 * all this factory adds.
 *
 * Unlike OpenCode, Junie has no documented per-process switch that makes it ask before an edit.
 * Its approvals come from `~/.junie/allowlist.json` and brave mode: terminal commands, MCP tools
 * and edits outside the project or to build scripts are asked by default, and ordinary edits
 * inside the project are not documented as asked. This factory writes no allowlist, since that
 * file is the person's own, so the post-turn audit is the fence that holds. `HarnessFence`
 * answers whatever Junie does ask.
 *
 * Bind it behind the tool a planner nominates:
 * ```kotlin
 * val harness = AcpCodeExecutor.junie(environment = mapOf("JUNIE_API_KEY" to key))
 * val executor = RoutedExecutor(
 *     default = FunctionExecutor.create(),
 *     bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness),
 * )
 * rosterRunHost(createEventApi = …, agentScope = …, executor = executor)
 * ```
 *
 * @param binary The Junie executable: a name on the PATH or an absolute path. The ACP registry's
 *   macOS archive puts it at `Applications/junie.app/Contents/MacOS/junie`.
 * @param workspace The checkout this executor works in when a request names none.
 * @param policy Shell access, cancel grace and timeouts.
 * @param environment Variables added to the agent's environment: `JUNIE_API_KEY`, or a BYOK
 *   provider key with `JUNIE_MODEL`, say.
 * @param extraArguments Arguments after `--acp=true` (`--model`, `--skip-update-check`).
 */
fun AcpCodeExecutor.Companion.junie(
    binary: String = "junie",
    workspace: ExecutionWorkspace? = null,
    policy: HarnessPolicy = HarnessPolicy(),
    environment: Map<String, String> = emptyMap(),
    extraArguments: List<String> = emptyList(),
): AcpCodeExecutor =
    AcpCodeExecutor(
        id = JUNIE_EXECUTOR_ID,
        displayName = "Junie (ACP)",
        transportFactory = {
            StdioAcpTransport(
                command = listOf(binary) + JUNIE_ACP_ARGUMENTS + extraArguments,
                directory = workspace?.let { File(it.baseDirectory) },
                environment = environment,
            )
        },
        observerFactory = { target -> GitWorkspaceObserver(File(target.baseDirectory)) },
        policy = policy,
        workspace = workspace,
    )
