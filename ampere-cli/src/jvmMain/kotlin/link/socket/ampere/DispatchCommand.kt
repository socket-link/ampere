package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.dispatch.AgentLaunchSpec
import link.socket.ampere.agents.execution.dispatch.AgentLauncher
import link.socket.ampere.agents.execution.dispatch.AgentRun
import link.socket.ampere.agents.execution.dispatch.AgentStop
import link.socket.ampere.agents.execution.dispatch.DispatchJournal
import link.socket.ampere.agents.execution.dispatch.DispatchPhase
import link.socket.ampere.agents.execution.dispatch.ModelChoice
import link.socket.ampere.agents.execution.dispatch.ModelRecommendations
import link.socket.ampere.agents.execution.dispatch.Refusal
import link.socket.ampere.agents.execution.dispatch.RunSupervisor
import link.socket.ampere.agents.execution.dispatch.Switchboard
import link.socket.ampere.agents.execution.dispatch.SwitchboardConfig
import link.socket.ampere.agents.execution.dispatch.SwitchboardListener
import link.socket.ampere.agents.execution.dispatch.SwitchboardReport
import link.socket.ampere.agents.execution.dispatch.SwitchboardShutdownHook
import link.socket.ampere.agents.execution.dispatch.WorktreePlan
import link.socket.ampere.agents.execution.dispatch.WorktreeProvisioner
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.integrations.git.RepositoryDetector
import link.socket.ampere.integrations.issues.github.GhMergeRequestLookup
import link.socket.ampere.terminal.TerminalFactory
import link.socket.ampere.work.linear.LinearDispatchWorkSource

/**
 * `ampere dispatch` — the Switchboard (AMPR-308): drain a wave's ready queue into
 * journaled, worktree-pinned, process-group-owned agent runs.
 *
 * ```
 * ampere dispatch --wave w1 --work-source https://mcp.example/mcp            # one pass
 * ampere dispatch --wave w1 --work-source https://mcp.example/mcp --watch    # poll until Ctrl+C
 * ```
 *
 * One pass evaluates the ready queue, claims and launches every ready ticket, then
 * stays to supervise those runs until each agent stops, and exits once they are
 * settled. `--watch` repeats the pass every `--poll` seconds while supervising, until
 * SIGINT or SIGTERM — which terminates the running agents' process groups, escalates
 * those interrupted runs, gives back the claims of any that had not started, and
 * writes the journal's clean-shutdown marker.
 *
 * Every write to the work source is public (the tracker mirrors to GitHub). Nothing
 * this command writes removes a gate, closes a ticket, records a verdict or merges a
 * pull request; see [Switchboard].
 *
 * Crash residue from an earlier supervisor is `ampere reconcile`'s job, not this
 * command's. It says so when it finds some.
 */
class DispatchCommand(
    private val contextProvider: () -> AmpereContext,
    private val workSourceCredentials: WorkSourceCredentials = WorkSourceCredentials(),
) : CliktCommand(
    name = "dispatch",
    help = "Dispatch a wave's ready tickets to agents in their own worktrees (the Switchboard)",
) {

    private val terminal = TerminalFactory.createTerminal()

    private val wave by option("--wave", help = "The wave to dispatch, e.g. w1 for label wave:w1").required()

    private val watch by option("--watch", help = "Poll the ready queue until stopped").flag(default = false)

    private val workSourceUri by option(
        "--work-source",
        help = "MCP work-source URI; the credential comes from the keychain (`ampere credential seed`), " +
            "or from \$AMPERE_WORK_SOURCE_TOKEN when set",
    ).required()

    private val project by option("--project", help = "Only dispatch tickets in this project")

    private val repository by option(
        "--repository", "-C",
        help = "Repository to create worktrees of (default: current directory)",
    )

    private val repoSlug by option(
        "--repo",
        help = "The repository as owner/name; tickets whose scope names no path in it are refused " +
            "(default: detected from origin when --repository is not given)",
    )

    private val base by option("--base", help = "Commit-ish each worktree branch starts from")
        .default(WorktreeProvisioner.DEFAULT_BASE)

    private val worktreeRoot by option(
        "--worktree-root",
        help = "Directory worktrees are created under (default ~/.ampere/supervisor/worktrees/<repository>)",
    )

    private val journalDirectory by option(
        "--journal-dir",
        help = "Supervisor journal directory (default ~/.ampere/supervisor/journal)",
    )

    private val logDirectory by option(
        "--log-dir",
        help = "Where each run's output is written (default ~/.ampere/supervisor/runs)",
    )

    private val agentBinary by option("--agent", help = "The agent CLI to launch").default("claude")

    private val permissionMode by option(
        "--permission-mode",
        help = "The agent's permission mode; a headless run has no one to approve a tool call",
    ).default("bypassPermissions")

    private val defaultModel by option(
        "--default-model",
        help = "Model when the ticket's recommendation line names none (${ModelRecommendations.MODELS.joinToString()})",
    ).default("sonnet")

    private val defaultEffort by option(
        "--default-effort",
        help = "Effort when the ticket's recommendation line names none (${ModelRecommendations.EFFORTS.joinToString()})",
    ).default("medium")

    private val pollSeconds by option("--poll", help = "Seconds between passes with --watch").long().default(300)

    private val silenceMinutes by option(
        "--silence-minutes",
        help = "Terminate and escalate a run that writes nothing for this long",
    ).long().default(RunSupervisor.DEFAULT_SILENCE_LIMIT.inWholeMinutes)

    private val maxAgents by option("--max-agents", help = "Hold at most this many dispatches at once").int()

    private val instanceId by option("--instance", help = "This supervisor's instance id")
        .default("switchboard-${ProcessHandle.current().pid()}-${UUID.randomUUID().toString().take(8)}")

    override fun run() = runBlocking {
        val context = contextProvider()
        val repoPath = (repository?.let { Paths.get(it) } ?: Paths.get("")).toAbsolutePath().normalize()
        if (!Files.isDirectory(repoPath)) {
            throw PrintMessage("--repository: not a directory: $repoPath", statusCode = 2, printError = true)
        }
        if (pollSeconds <= 0 || silenceMinutes <= 0) {
            throw PrintMessage("--poll and --silence-minutes must be positive", statusCode = 2, printError = true)
        }
        val slug = repoSlug ?: if (repository == null) RepositoryDetector.detectRepository() else null
        val home = File(System.getProperty("user.home") ?: ".").toPath()
        val supervisorHome = home.resolve(".ampere/supervisor")
        val journalDir = journalDirectory?.let { Paths.get(it) } ?: supervisorHome.resolve("journal")
        val logDir = logDirectory?.let { Paths.get(it) } ?: supervisorHome.resolve("runs")
        val worktrees = worktreeRoot?.let { Paths.get(it) }
            ?: supervisorHome.resolve("worktrees").resolve(repoPath.fileName?.toString() ?: "repository")

        terminal.println(bold(cyan("⚡ AMPERE Switchboard")))
        terminal.println("  Wave:        $wave${if (watch) dim(" (watching every ${pollSeconds}s)") else ""}")
        terminal.println("  Repository:  $repoPath${slug?.let { dim(" ($it)") } ?: ""}")
        terminal.println("  Worktrees:   $worktrees ${dim("from $base")}")
        terminal.println("  Agent:       $agentBinary ${dim("(default $defaultModel, $defaultEffort; $permissionMode)")}")
        terminal.println("  Work source: $workSourceUri")
        terminal.println("  Instance:    $instanceId")

        val workSource = WorkSourceConnection.open(
            uri = workSourceUri,
            instanceId = instanceId,
            credentials = workSourceCredentials,
            terminal = terminal,
            why = "Claiming a ticket is a write.",
        )
        terminal.println()

        val eventApi = context.environmentService.createEventApi(SWITCHBOARD_AGENT_ID)
        val journal = DispatchJournal.open(directory = journalDir, instanceId = instanceId, eventApi = eventApi)
        warnAboutUnreconciled(journal)

        val switchboard = Switchboard(
            config = SwitchboardConfig(
                wave = wave,
                logDirectory = logDir,
                repositorySlug = slug,
                defaultModel = ModelChoice(model = defaultModel, effort = defaultEffort),
                pollInterval = pollSeconds.seconds,
                maxAgents = maxAgents,
            ),
            workSource = LinearDispatchWorkSource(workSource, project = project),
            journal = journal,
            worktrees = WorktreeProvisioner(repository = repoPath, root = worktrees, base = base),
            launcher = AgentLauncher(AgentLaunchSpec.claudeCode(binary = agentBinary, permissionMode = permissionMode)),
            supervisor = RunSupervisor(silenceLimit = silenceMinutes.minutes),
            mergeRequests = GhMergeRequestLookup(repoPath, repository = slug),
            eventApi = eventApi,
            listener = ConsoleListener(),
        )

        val hook = SwitchboardShutdownHook.install(switchboard)
        val outcome = switchboard.run(watch = watch)
        SwitchboardShutdownHook.remove(hook)
        printReport(outcome, logDir)
    }

    /** Point at residue from a supervisor that never shut down cleanly. Reported, never acted on. */
    private suspend fun warnAboutUnreconciled(journal: DispatchJournal) {
        val unsettled = journal.scanAll()
            .filter { it.instanceId != journal.instanceId && !it.cleanShutdown && it.reconciliation == null }
            .filter { it.suspectDispatches().isNotEmpty() || it.quarantined.isNotEmpty() }
        if (unsettled.isEmpty()) return
        terminal.println(
            yellow(
                "⚠ ${unsettled.size} earlier supervisor journal(s) were never shut down cleanly; " +
                    "run `ampere reconcile` to settle what they left",
            ),
        )
        terminal.println()
    }

    private fun printReport(report: SwitchboardReport, logDir: Path) {
        terminal.println()
        terminal.println(bold("Switchboard ${report.instanceId}"))
        terminal.println("  Dispatched: ${report.dispatched.size}  Settled: ${report.settled.size}  Released: ${report.released.size}")
        if (report.refused.isNotEmpty()) terminal.println("  Still refused: ${report.refused.joinToString { it.ticketId }}")
        terminal.println("  Run logs:   ${logDir.resolve(report.instanceId)}")
        report.owed.forEach { (ticket, why) -> terminal.println(red("  ⚠ $ticket owed: $why")) }
        terminal.println(
            if (report.cleanShutdown) {
                green("✓ Journal marked clean: nothing is owed")
            } else {
                red("⚠ Journal left unmarked: run `ampere reconcile` to settle what is owed")
            },
        )
    }

    /** Prints the Switchboard's progress as it happens. */
    private inner class ConsoleListener : SwitchboardListener {

        override fun onNote(message: String) {
            terminal.println("  ${dim("·")} $message")
        }

        override fun onRefused(refusal: Refusal) {
            terminal.println("  ${dim("–")} ${refusal.ticketId} ${yellow(refusal.reason.name)} ${dim(refusal.detail)}")
        }

        override fun onClaimLost(ticketId: String, detail: String) {
            terminal.println("  ${dim("–")} $ticketId ${yellow("CLAIM_LOST")} ${dim(detail)}")
        }

        override fun onDispatched(ticketId: String, plan: WorktreePlan, model: ModelChoice, address: CancellationAddress) {
            terminal.println(
                green("  ▶ ") + bold(ticketId) +
                    " ${model.model}/${model.effort} on ${plan.branch} " +
                    dim("(pgid ${address.processGroupId ?: "none"}, ${plan.path})"),
            )
        }

        override fun onStopped(ticketId: String, run: AgentRun) {
            val how = when (run.stop) {
                AgentStop.EXITED -> "exited ${run.exitCode ?: "?"}"
                AgentStop.SILENCED -> "silent past the limit; group ${run.termination?.name ?: "?"}"
                AgentStop.SHUTDOWN -> "stopped by shutdown; group ${run.termination?.name ?: "?"}"
            }
            terminal.println("  ■ ${bold(ticketId)} $how")
        }

        override fun onSettled(ticketId: String, phase: DispatchPhase?, detail: String) {
            val mark = when (phase) {
                DispatchPhase.DONE -> green("  ✓ ")
                DispatchPhase.ESCALATED -> red("  ⚠ ")
                else -> dim("  ↩ ")
            }
            terminal.println(mark + bold(ticketId) + " ${phase?.name ?: "RELEASED"} ${dim(detail)}")
        }
    }

    private companion object {

        /** Attribution for the events the Switchboard publishes. */
        const val SWITCHBOARD_AGENT_ID = "switchboard"
    }
}
