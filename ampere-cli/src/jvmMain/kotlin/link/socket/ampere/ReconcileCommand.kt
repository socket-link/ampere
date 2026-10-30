package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.dispatch.ClaimRegistry
import link.socket.ampere.agents.execution.dispatch.DispatchDisposition
import link.socket.ampere.agents.execution.dispatch.DispatchJournal
import link.socket.ampere.agents.execution.dispatch.ReconciliationOutcome
import link.socket.ampere.agents.execution.dispatch.ReconciliationSource
import link.socket.ampere.agents.execution.dispatch.StartupReconciler
import link.socket.ampere.agents.execution.dispatch.WorktreeRepair
import link.socket.ampere.integrations.issues.github.GhMergeRequestLookup
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.EgressClass
import link.socket.ampere.link.InMemoryLinkStore
import link.socket.ampere.link.Link
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkResolutionService
import link.socket.ampere.link.PlatformTarget
import link.socket.ampere.link.Transport
import link.socket.ampere.plug.PlugContext
import link.socket.ampere.terminal.TerminalFactory
import link.socket.ampere.work.linear.LinearClaimRegistry
import link.socket.ampere.work.linear.LinearWorkSource
import link.socket.ampere.work.linear.SupervisorInstanceId

/**
 * `ampere reconcile` — the startup reconciliation pass (AMPR-310, AMPR-291
 * mechanism M-C).
 *
 * Recovery after a supervisor was killed: reap the agent process groups it left
 * running, repair the git worktrees and branches they were writing to, release the
 * work-source claims no timeout will ever clear, and report whatever only a person
 * can decide about. Safe to run at any time, including over the top of an
 * interrupted run of itself — every step is a read, an idempotent write, or a
 * compensation guarded by a read-back.
 *
 * ### What authorises a deletion
 *
 * The journal, not the flags. The pass only ever touches a worktree path or a
 * branch name that a supervisor *recorded before creating it*, so pointing
 * `--repository` at an unrelated checkout finds nothing to do rather than finding
 * things to delete. A path the repository does not know is reported, never removed.
 *
 * ### The work source
 *
 * Step 4 runs only with `--work-source`. Without it the pass still does every
 * local step and reports the claims it could not reach, rather than reporting
 * success it did not achieve.
 *
 * The credential comes from the keychain, where `ampere credential seed` put it
 * (AMPR-464), and is refreshed as it nears expiry, so the command needs nothing
 * in the environment. `$AMPERE_WORK_SOURCE_TOKEN`, when set, overrides it with a
 * static token for one run. See [WorkSourceCredentials]. The Link grant is still
 * built in memory for the length of the command.
 *
 * ```
 * ampere reconcile
 * ampere reconcile --repository ~/code/ampere --work-source https://mcp.example/mcp
 * ampere reconcile --skip-worktrees            # claims only
 * ```
 */
class ReconcileCommand(
    private val contextProvider: () -> AmpereContext,
    private val workSourceCredentials: WorkSourceCredentials = WorkSourceCredentials(),
) : CliktCommand(
    name = "reconcile",
    help = "Recover from an interrupted supervisor: reap orphans, repair worktrees, release claims",
) {

    private val terminal = TerminalFactory.createTerminal()

    private val journalDirectory by option(
        "--journal-dir",
        help = "Supervisor journal directory (default ~/.ampere/supervisor/journal)",
    )

    private val repository by option(
        "--repository", "-C",
        help = "Repository whose worktrees and branches may be repaired (default: current directory)",
    )

    private val skipWorktrees by option(
        "--skip-worktrees",
        help = "Do not inspect or repair any worktree or branch",
    ).flag(default = false)

    private val workSourceUri by option(
        "--work-source",
        help = "MCP work-source URI; the credential comes from the keychain (`ampere credential seed`), " +
            "or from \$AMPERE_WORK_SOURCE_TOKEN when set",
    )

    private val releaseUnjournaledClaims by option(
        "--release-unjournaled-claims",
        help = "In the no-journal fallback, release claims as well as reporting them",
    ).flag(default = false)

    private val instanceId by option(
        "--instance",
        help = "This pass's own supervisor instance id",
    ).default("reconcile-${ProcessHandle.current().pid()}")

    override fun run() = runBlocking {
        val context = contextProvider()
        val directory = journalDirectory?.let { Paths.get(it) } ?: defaultJournalDirectory()
        val repoPath = repository?.let { Paths.get(it) } ?: Paths.get("").toAbsolutePath()

        terminal.println(bold(cyan("⚡ AMPERE reconciliation pass")))
        terminal.println("  Journal:     $directory")
        terminal.println("  Repository:  ${if (skipWorktrees) dim("(skipped)") else repoPath.toString()}")
        val workSourceLabel = workSourceUri ?: dim("(not configured — step 4 reported, not run)")
        terminal.println("  Work source: $workSourceLabel")
        terminal.println("  Instance:    $instanceId")

        // Opened before the header closes, so the credential line sits under the work source.
        val claims = workSourceUri?.let { uri -> openWorkSource(uri) }
        terminal.println()
        val eventApi = context.environmentService.createEventApi(RECONCILER_AGENT_ID)

        val outcome = StartupReconciler(
            journal = DispatchJournal.open(
                directory = directory,
                instanceId = instanceId,
                eventApi = eventApi,
            ),
            // Skipping the worktrees skips the forge too: without the local repair the
            // branch phase never runs, so a merge-request query would answer nothing.
            worktrees = if (skipWorktrees) null else WorktreeRepair(repoPath),
            claims = claims,
            mergeRequests = if (skipWorktrees) null else GhMergeRequestLookup(repoPath),
            eventApi = eventApi,
            releaseUnjournaledClaims = releaseUnjournaledClaims,
        ).reconcile()

        report(outcome)
    }

    /**
     * Build the work-source adapter for this one command, on the credential
     * [workSourceCredentials] opens: the seeded keychain item by default, refreshed
     * as it nears expiry, or `$AMPERE_WORK_SOURCE_TOKEN` as an explicit override.
     */
    private suspend fun openWorkSource(uri: String): ClaimRegistry {
        val manifest = LinearWorkSource.manifest(uri)
        val linkId = WorkSourceCredentials.linkIdFor(uri)

        val credentials = workSourceCredentials.open(linkId, uri).getOrElse { cause ->
            throw PrintMessage(
                "--work-source: ${cause.message} Releasing a claim is a write.",
                statusCode = 1,
                printError = true,
            )
        }
        if (credentials.origin == WorkSourceCredentials.Origin.ENVIRONMENT_OVERRIDE) {
            terminal.println(
                yellow("  Credential:  \$${WorkSourceCredentials.TOKEN_ENV} (override; not refreshed, not stored)"),
            )
        } else {
            terminal.println("  Credential:  keychain ${dim("(${CredentialRef.aliasFor(linkId)})")}")
        }

        val links = InMemoryLinkStore(
            links = listOf(
                Link(
                    id = linkId,
                    transport = Transport.MCP,
                    direction = LinkDirection.READ_WRITE,
                    egress = EgressClass.ThirdParty(provider = LinearWorkSource.DEPENDENCY_NAME),
                    scope = manifest.emits,
                    credentialRef = CredentialRef.forLink(linkId),
                ),
            ),
        )
        links.grant(manifest.id, linkId).getOrThrow()

        val plugContext = PlugContext.create(
            manifest = manifest,
            credentialBinding = credentials.binding,
            linkResolutionService = LinkResolutionService(
                linkStore = links,
                platform = PlatformTarget.JVM_DESKTOP,
            ),
            credentialRefresher = credentials.refresher,
        ).getOrThrow()

        // A server that never came up leaves no tools, which `open` would report as schema
        // drift. Say what actually happened — a refused or unrefreshable credential, usually.
        plugContext.serverFailures.firstOrNull()?.let { failure ->
            throw PrintMessage(
                "--work-source: could not connect to $uri: ${failure.cause?.message ?: "unknown failure"}",
                statusCode = 1,
                printError = true,
            )
        }

        val workSource = LinearWorkSource.open(
            plugContext = plugContext,
            linkId = linkId,
            instanceId = SupervisorInstanceId(instanceId),
        ).getOrThrow()

        return LinearClaimRegistry(workSource)
    }

    private fun report(outcome: ReconciliationOutcome) {
        terminal.println(
            when (outcome.source) {
                ReconciliationSource.JOURNAL ->
                    "Read ${outcome.journalsRead.size} journal(s); " +
                        "${outcome.journalsSettled.size} settled"

                ReconciliationSource.DEGRADED_CLAIM_SCAN ->
                    yellow("Degraded: no journal, so suspects came from a work-source claim scan")

                ReconciliationSource.NO_EVIDENCE -> dim("Nothing to reconcile")
            },
        )

        outcome.notes.forEach { terminal.println("  ${dim("·")} $it") }

        if (outcome.dispositions.isEmpty()) {
            terminal.println(green("✓ No suspect dispatch found"))
            return
        }

        terminal.println()
        outcome.dispositions.forEach { terminal.println(line(it)) }

        terminal.println()
        val held = outcome.held.size
        terminal.println(
            if (outcome.isSettled) {
                green("✓ ${outcome.dispositions.size} dispatch(es) reconciled; nothing is owed")
            } else {
                red("⚠ $held of ${outcome.dispositions.size} dispatch(es) need a human decision")
            },
        )
    }

    private fun line(disposition: DispatchDisposition): String = buildString {
        append(if (disposition.isSettled) green("  ✓ ") else red("  ⚠ "))
        append(bold(disposition.ticketId))
        append(" ${dim(disposition.supervisorInstanceId)}")
        disposition.phase?.let { append(" ${dim(it.name)}") }
        appendLine()
        append("      worktree=${disposition.worktree.name}")
        append(" branch=${disposition.branchVerdict.name}")
        append(" claim=${disposition.claim.name}")
        disposition.reaped?.let { append(" group=${it.name}") }
        disposition.mergeRequest?.let { append(" mr=${it.url}") }
        if (disposition.mayOpenMergeRequest == true) append(" ${dim("(a redispatch may open an MR)")}")
        disposition.notes.forEach {
            appendLine()
            append("      ${dim("·")} $it")
        }
    }

    private fun defaultJournalDirectory(): Path {
        val home = System.getProperty("user.home")
            ?: System.getProperty("user.dir")
            ?: "."
        return File(home, ".ampere/supervisor/journal").toPath()
    }

    private companion object {

        /** Attribution for the events the pass publishes. */
        const val RECONCILER_AGENT_ID = "startup-reconciler"
    }
}
