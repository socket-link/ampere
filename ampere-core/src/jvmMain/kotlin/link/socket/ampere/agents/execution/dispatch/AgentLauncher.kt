package link.socket.ampere.agents.execution.dispatch

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * The command a dispatched agent runs, as a template.
 *
 * Each argument may carry placeholders, substituted whole or inside a larger string:
 * `{model}`, `{effort}`, `{prompt}`, `{ticket}`, `{worktree}`, `{context}` (the
 * context file's path relative to the worktree). Arguments are passed as a list,
 * never through a shell, so a ticket title or a prompt cannot become a command.
 *
 * @property environment Extra environment for the agent. The worktree pin
 *   ([AgentLauncher]) is applied after it, so nothing here can move the agent out.
 */
data class AgentLaunchSpec(
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(command.isNotEmpty()) { "An agent launch command must not be empty" }
    }

    companion object {

        /**
         * Claude Code, headless, in the worktree.
         *
         * `--output-format stream-json` (which `--print` requires `--verbose` for) is not
         * decoration: it makes the agent write a line per message and tool call, which
         * is what gives the silence rule something to measure. With plain `--print` the
         * agent writes nothing until it finishes, and every long run would look hung.
         *
         * @param permissionMode The agent's permission mode. A headless run has no one to
         *   approve a tool call, so anything stricter than the operator's interactive
         *   setting stalls the run at its first shell command.
         */
        fun claudeCode(
            binary: String = "claude",
            permissionMode: String = "bypassPermissions",
        ): AgentLaunchSpec = AgentLaunchSpec(
            command = listOf(
                binary,
                "--print",
                "--verbose",
                "--output-format",
                "stream-json",
                "--model",
                "{model}",
                "--effort",
                "{effort}",
                "--permission-mode",
                permissionMode,
                "{prompt}",
            ),
        )
    }
}

/** Everything one launch needs to know about its dispatch. */
data class AgentLaunch(
    val ticketId: String,
    val worktree: Path,
    val contextPath: String,
    val model: ModelChoice,
)

/**
 * An agent spawned in its own process group, held at a gate until [release].
 *
 * The gate is how the process-group id reaches the journal *before* the agent runs
 * (AMPR-308, Context §5; the DispatchJournal invariant "the record is written before
 * the work it describes"). [ProcessGroups.start] can only report an address once a
 * process exists, so the process that is spawned is a one-line `sh` gate that blocks
 * reading its stdin and then `exec`s the agent in place — same PID, same group, same
 * start time, so the address recorded for the gate is the agent's address. The
 * supervisor journals the address and only then writes `go`.
 *
 * A supervisor that dies between spawn and release closes the gate's stdin by dying,
 * and the gate exits [ABANDONED_EXIT] without the agent ever having run: there is no
 * window in which an unrecorded agent can be doing work.
 */
class GatedAgent internal constructor(val grouped: GroupedProcess) {

    /** Let the agent start. Its stdin is closed afterwards: a dispatched agent reads nothing from it. */
    fun release() {
        grouped.process.outputStream.use { stdin ->
            stdin.write("$GO\n".toByteArray())
            stdin.flush()
        }
    }

    companion object {

        /** The gate's exit status when it was closed without being released. */
        const val ABANDONED_EXIT: Int = 125

        private const val GO = "go"

        /** `$0` is the gate's name; `"$@"` is the agent command, run only after `go`. */
        internal const val GATE_SCRIPT: String =
            "IFS= read -r line || exit $ABANDONED_EXIT; [ \"\$line\" = $GO ] || exit $ABANDONED_EXIT; exec \"\$@\""
    }
}

/**
 * Builds and spawns a dispatched agent (AMPR-308, Context §5).
 *
 * ### The worktree pin
 *
 * The agent's working directory is the worktree, and `PWD` says so — never the
 * supervisor's own directory, and never the core default workspace that AMPR-300's
 * finding F2 showed escaping every sandbox. That is the whole of the pin for an agent
 * that is not Ampere. For one that is, Ampere resolves its workspace from
 * `--workspace`, then a config file's `workspace:` key, then the working directory;
 * so [checkPinned] refuses a worktree whose own Ampere config points the workspace
 * anywhere outside it. The pin is the supervisor's containment, whatever the core
 * default becomes.
 *
 * ### No kickoff prose
 *
 * The prompt is a pointer to the attached ticket and nothing else (operator answer
 * 5 on AMPR-288): the same attachment line an agent dispatched by hand receives.
 */
class AgentLauncher(private val spec: AgentLaunchSpec) {

    /** The kickoff prompt for [launch]. */
    fun prompt(launch: AgentLaunch): String =
        "The user has attached these files. Read them before proceeding.\n- ${launch.contextPath}"

    /** The process [launch] runs, not yet started, ungated. */
    fun builder(launch: AgentLaunch): ProcessBuilder {
        val values = mapOf(
            "{model}" to launch.model.model,
            "{effort}" to launch.model.effort,
            "{prompt}" to prompt(launch),
            "{ticket}" to launch.ticketId,
            "{worktree}" to launch.worktree.toString(),
            "{context}" to launch.contextPath,
        )
        val command = spec.command.map { argument ->
            values.entries.fold(argument) { acc, (placeholder, value) -> acc.replace(placeholder, value) }
        }

        val builder = ProcessBuilder(command)
            .directory(launch.worktree.toFile())
            .redirectErrorStream(true)
        val environment = builder.environment()
        environment.putAll(spec.environment)
        environment["PWD"] = launch.worktree.toString()
        environment.remove("OLDPWD")
        // A grouped command on the bash path is a background job of the supervisor's
        // session; anything that read the terminal would stop with SIGTTIN.
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["GH_PROMPT_DISABLED"] = "1"
        return builder
    }

    /**
     * Spawn [launch] in its own process group, held at the gate.
     *
     * @throws IOException when the process cannot be started.
     */
    fun startGated(launch: AgentLaunch): GatedAgent {
        val agent = builder(launch)
        val gated = ProcessBuilder(listOf(SHELL, "-c", GatedAgent.GATE_SCRIPT, GATE_NAME) + agent.command())
            .directory(agent.directory())
            .redirectErrorStream(true)
        gated.environment().clear()
        gated.environment().putAll(agent.environment())
        return GatedAgent(ProcessGroups.start(gated))
    }

    companion object {

        private const val SHELL = "/bin/sh"

        private const val GATE_NAME = "ampere-dispatch-gate"

        /** The config files Ampere reads a `workspace:` key from, relative to its working directory. */
        val AMPERE_CONFIG_FILES: List<String> = listOf(
            "ampere.yaml",
            "ampere.yml",
            ".ampere/config.yaml",
            ".ampere/config.yml",
        )

        /**
         * Why [worktree]'s own Ampere configuration would move an Ampere agent's
         * workspace outside it, or null when it would not.
         *
         * A config that cannot be read is a refusal too: an unreadable config is one
         * whose `workspace:` key nobody can rule out.
         */
        fun checkPinned(worktree: Path): String? {
            val root = worktree.toAbsolutePath().normalize()
            AMPERE_CONFIG_FILES.forEach { name ->
                val file = root.resolve(name)
                if (!Files.isRegularFile(file)) return@forEach
                val declared = try {
                    val content = Files.readString(file)
                    if (content.isBlank()) return@forEach
                    PIN_YAML.decodeFromString(WorkspaceKey.serializer(), content).workspace
                } catch (e: Exception) {
                    return "$name could not be read, so its workspace setting is unknown"
                } ?: return@forEach

                val expanded = if (declared.startsWith("~")) {
                    System.getProperty("user.home") + declared.removePrefix("~")
                } else {
                    declared
                }
                val resolved = root.resolve(Paths.get(expanded)).normalize()
                if (!resolved.startsWith(root)) {
                    return "$name sets the Ampere workspace outside the worktree"
                }
            }
            return null
        }

        private val PIN_YAML = Yaml(configuration = YamlConfiguration(strictMode = false))

        @Serializable
        private data class WorkspaceKey(val workspace: String? = null)
    }
}
