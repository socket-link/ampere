package link.socket.ampere.agents.execution.executor

import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.ToolId

/**
 * One coding harness as a YAML config names it (AMPR-497): the `harnesses:` section's value
 * for one key.
 *
 * ```yaml
 * harnesses:
 *   opencode:                      # kind opencode: `opencode acp`, recorded as `opencode`
 *     binary: /opt/homebrew/bin/opencode
 *     prompt-timeout-seconds: 600
 *     environment:
 *       OPENCODE_API_KEY: ${OPENCODE_API_KEY}   # read from the process environment
 *   my-agent:                      # kind acp: any ACP agent, recorded under its key
 *     kind: acp
 *     binary: my-agent
 *     arguments: [--acp]
 *     shell: allow
 *     tools: [harness_code_change]
 * ```
 *
 * The section is the YAML route to what a consumer otherwise writes in code: [build] turns
 * each entry into an [AcpCodeExecutor] and [routedOver] puts them behind a [RoutedExecutor]
 * over the default executor, bound to the tool ids each entry lists. A config with no entries
 * routes nothing, and [routedOver] then returns the default executor itself.
 *
 * Secrets never sit in the file: an [environment] value may be, or contain, `${NAME}`, which is
 * replaced from the process environment when the executor is built. A name that is not set is
 * an error that names the variable and never a value.
 *
 * @property kind [HarnessKind.OpenCode] (the default) fills the id, binary and display name from
 *   [AcpCodeExecutor.Companion.openCode]; [HarnessKind.Acp] is any other ACP agent.
 * @property id The `Executor.id` the harness is recorded under. Filled from the section's key by
 *   [keyed] for [HarnessKind.Acp]; for [HarnessKind.OpenCode] it is always `opencode` (AMPR-444
 *   D8), so it may be left out, and any other value is refused.
 * @property binary The executable: a name on the PATH or an absolute path. Defaults to
 *   `opencode` for [HarnessKind.OpenCode]; required for [HarnessKind.Acp].
 * @property arguments The arguments after [binary]. `[acp]` by default; for
 *   [HarnessKind.OpenCode] they must begin with `acp`, the launch D8 fixed.
 * @property shell Whether the harness may run shell commands. [HarnessShell.Deny] by default.
 * @property cancelGraceSeconds [HarnessPolicy.cancelGrace], in seconds.
 * @property promptTimeoutSeconds [HarnessPolicy.promptTimeout], in seconds; absent is no limit.
 * @property tools The tool ids routed to this harness: bare (`harness_code_change`) or one
 *   seat's (`scout/harness_code_change`). No two harnesses may bind the same id.
 * @property environment Variables added to the harness's environment, `${NAME}` resolved.
 */
@Serializable
data class HarnessConfig(
    val kind: HarnessKind = HarnessKind.OpenCode,
    val id: ExecutorId? = null,
    val binary: String? = null,
    val arguments: List<String> = listOf(ACP_ARGUMENT),
    val shell: HarnessShell = HarnessShell.Deny,
    @SerialName("cancel-grace-seconds")
    val cancelGraceSeconds: Long = DEFAULT_CANCEL_GRACE_SECONDS,
    @SerialName("prompt-timeout-seconds")
    val promptTimeoutSeconds: Long? = null,
    val tools: List<ToolId> = listOf(HARNESS_CODE_CHANGE_TOOL_ID),
    val environment: Map<String, String> = emptyMap(),
) {

    /** The id this harness is recorded under; null only for an unkeyed [HarnessKind.Acp] entry. */
    val executorId: ExecutorId?
        get() = when (kind) {
            HarnessKind.OpenCode -> OPENCODE_EXECUTOR_ID
            HarnessKind.Acp -> id
        }

    /** The executable this entry launches; null only for an [HarnessKind.Acp] entry with none. */
    val resolvedBinary: String?
        get() = binary ?: if (kind == HarnessKind.OpenCode) OPENCODE_DEFAULT_BINARY else null

    /** [shell], [cancelGraceSeconds] and [promptTimeoutSeconds] as the executor's policy. */
    val policy: HarnessPolicy
        get() = HarnessPolicy(
            shell = when (shell) {
                HarnessShell.Deny -> HarnessPolicy.ShellAccess.Deny
                HarnessShell.Allow -> HarnessPolicy.ShellAccess.Allow
            },
            cancelGrace = cancelGraceSeconds.seconds,
            promptTimeout = promptTimeoutSeconds?.seconds,
        )

    /** What is wrong with this entry on its own, one message per problem; empty when nothing is. */
    fun problems(): List<String> = buildList {
        val label = executorId ?: "(unkeyed)"
        when (kind) {
            HarnessKind.OpenCode -> {
                if (id != null && id != OPENCODE_EXECUTOR_ID) {
                    add("harness '$label': kind opencode is recorded as '$OPENCODE_EXECUTOR_ID', not '$id'")
                }
                if (arguments.firstOrNull() != ACP_ARGUMENT) {
                    add(
                        "harness '$label': kind opencode launches '$OPENCODE_DEFAULT_BINARY $ACP_ARGUMENT', " +
                            "so its arguments must begin with '$ACP_ARGUMENT'",
                    )
                }
            }
            HarnessKind.Acp -> {
                if (id.isNullOrBlank()) add("harness '$label': kind acp needs an id")
                if (binary.isNullOrBlank()) add("harness '$label': kind acp needs a binary")
            }
        }
        if (binary != null && binary.isBlank()) add("harness '$label': binary must not be blank")
        if (tools.isEmpty()) add("harness '$label': binds no tools")
        if (tools.any { it.isBlank() }) add("harness '$label': a tool id is blank")
        if (cancelGraceSeconds < 0) add("harness '$label': cancel-grace-seconds must not be negative")
        if (promptTimeoutSeconds != null && promptTimeoutSeconds <= 0) {
            add("harness '$label': prompt-timeout-seconds must be positive")
        }
    }

    /**
     * The [AcpCodeExecutor] this entry describes, bound to [workspace] — the checkout a step runs
     * in when its request names none, which is every hosted-run step.
     *
     * @param processEnvironment Where `${NAME}` in [environment] is read from.
     * @throws IllegalArgumentException when the entry has [problems], or names a variable that
     *   [processEnvironment] does not set.
     */
    fun toExecutor(
        workspace: ExecutionWorkspace?,
        processEnvironment: Map<String, String> = System.getenv(),
    ): AcpCodeExecutor {
        val problems = problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val recordedId = checkNotNull(executorId)
        val executable = checkNotNull(resolvedBinary)
        val resolved = resolveEnvironment(recordedId, processEnvironment)
        return when (kind) {
            HarnessKind.OpenCode -> AcpCodeExecutor.openCode(
                binary = executable,
                workspace = workspace,
                policy = policy,
                environment = resolved,
                extraArguments = arguments.drop(1),
            )
            HarnessKind.Acp -> AcpCodeExecutor(
                id = recordedId,
                displayName = "$recordedId (ACP)",
                transportFactory = {
                    StdioAcpTransport(
                        command = listOf(executable) + arguments,
                        directory = workspace?.let { File(it.baseDirectory) },
                        environment = resolved,
                    )
                },
                observerFactory = { target -> GitWorkspaceObserver(File(target.baseDirectory)) },
                policy = policy,
                workspace = workspace,
            )
        }
    }

    private fun resolveEnvironment(
        recordedId: ExecutorId,
        processEnvironment: Map<String, String>,
    ): Map<String, String> = environment.mapValues { (key, value) ->
        VARIABLE.replace(value) { match ->
            val name = match.groupValues[1]
            requireNotNull(processEnvironment[name]) {
                "harness '$recordedId': environment $key reads \${$name}, which is not set"
            }
        }
    }

    companion object {
        const val ACP_ARGUMENT: String = "acp"
        const val OPENCODE_DEFAULT_BINARY: String = "opencode"
        const val DEFAULT_CANCEL_GRACE_SECONDS: Long = 5

        private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")
    }
}

/** Which harness a [HarnessConfig] entry launches. */
@Serializable
enum class HarnessKind {
    /** OpenCode: `opencode acp`, recorded as `opencode`. */
    @SerialName("opencode")
    OpenCode,

    /** Any other agent that speaks ACP on stdio, recorded under the entry's id. */
    @SerialName("acp")
    Acp,
}

/** Whether a harness may run shell commands; [HarnessPolicy.ShellAccess] as YAML spells it. */
@Serializable
enum class HarnessShell {
    @SerialName("deny")
    Deny,

    @SerialName("allow")
    Allow,
}

/** One configured harness and the executor built from it, kept so a status can ask its health. */
class BoundHarness(
    val config: HarnessConfig,
    val executor: AcpCodeExecutor,
)

/**
 * The `harnesses:` section's entries in key order, each [HarnessKind.Acp] entry's
 * [HarnessConfig.id] filled from its key when it names none. An [HarnessKind.OpenCode] entry is
 * recorded as `opencode` whatever its key, so its key is only a label.
 */
fun Map<String, HarnessConfig>.keyed(): List<HarnessConfig> =
    map { (key, config) ->
        if (config.id == null && config.kind == HarnessKind.Acp) config.copy(id = key) else config
    }

/**
 * What is wrong with these entries, each on its own and together: two harnesses under one
 * executor id, or two binding one tool id. Empty when nothing is.
 */
fun List<HarnessConfig>.harnessProblems(): List<String> {
    val problems = flatMap { config -> config.problems() }.toMutableList()
    val ids = mapNotNull { config -> config.executorId }
    ids.distinct().filter { id -> ids.count { it == id } > 1 }.forEach { id ->
        problems += "harness id '$id' is configured more than once"
    }
    val bindings = flatMap { config -> config.tools.map { tool -> tool to (config.executorId ?: "(unkeyed)") } }
    bindings.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.forEach { (tool, holders) ->
        problems += "tool '$tool' is bound to more than one harness: ${holders.joinToString()}"
    }
    return problems
}

/**
 * Every entry's executor, bound to [workspace].
 *
 * @throws IllegalArgumentException with every one of [harnessProblems], or when an entry's
 *   environment names a variable [processEnvironment] does not set.
 */
fun List<HarnessConfig>.build(
    workspace: ExecutionWorkspace?,
    processEnvironment: Map<String, String> = System.getenv(),
): List<BoundHarness> {
    val problems = harnessProblems()
    require(problems.isEmpty()) { problems.joinToString("; ") }
    return map { config -> BoundHarness(config, config.toExecutor(workspace, processEnvironment)) }
}

/**
 * [default] with each harness bound to the tool ids it lists, behind a [RoutedExecutor]; with no
 * harnesses, [default] itself — a config without the section changes nothing.
 */
fun List<BoundHarness>.routedOver(default: Executor): Executor =
    if (isEmpty()) {
        default
    } else {
        RoutedExecutor(
            default = default,
            bindings = flatMap { bound -> bound.config.tools.map { tool -> tool to bound.executor } }.toMap(),
        )
    }

/** [build], then [routedOver]: the executor a consumer hands to `Ampere.fromEnvironment`. */
fun List<HarnessConfig>.routedOver(
    default: Executor,
    workspace: ExecutionWorkspace?,
    processEnvironment: Map<String, String> = System.getenv(),
): Executor = build(workspace, processEnvironment).routedOver(default)
