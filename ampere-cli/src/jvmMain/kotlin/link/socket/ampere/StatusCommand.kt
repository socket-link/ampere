package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextStyles.underline
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.execution.executor.BoundHarness
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.api.AmpereInstance
import link.socket.ampere.terminal.TerminalFactory
import kotlin.time.Duration.Companion.hours

/**
 * Display a comprehensive dashboard of system state.
 * This is your situational awareness command.
 */
class StatusCommand(
    private val ampere: AmpereInstance,
    /** The coding harnesses the config binds (AMPR-497); each is asked its health. */
    private val harnesses: List<BoundHarness> = emptyList(),
    /** Why the config's `harnesses:` section bound nothing, when it could not be bound. */
    private val harnessError: String? = null,
) : CliktCommand(
    name = "status",
    help = "Show system-wide status dashboard"
) {
    private val verbose by option(
        "--verbose", "-v",
        help = "Show detailed information including recent events and ticket descriptions"
    ).flag()

    private val terminal = TerminalFactory.createTerminal()

    override fun run() = runBlocking {
        // Fetch data from multiple services concurrently
        val threadsDeferred = async { ampere.threads.list() }
        val ticketsDeferred = async { ampere.tickets.list() }
        val snapshotDeferred = async { ampere.status.snapshot() }
        val harnessesDeferred = async { harnessStatuses(harnesses) }

        val threadsResult = threadsDeferred.await()
        val ticketsResult = ticketsDeferred.await()
        val workspace = snapshotDeferred.await().getOrNull()?.workspace

        // Human-readable dashboard
        outputDashboard(threadsResult, ticketsResult, verbose, workspace)
        outputHarnesses(harnessesDeferred.await())
    }

    /**
     * One line per configured harness (AMPR-497): its id, its binary, and available — with the
     * agent's version when it gave one — or the reason it is not. Nothing when the config names
     * no harnesses and bound them all.
     */
    private fun outputHarnesses(statuses: List<HarnessStatus>) {
        if (statuses.isEmpty() && harnessError == null) return
        terminal.println()
        terminal.println(bold("🔌 Harnesses"))
        harnessError?.let { error -> terminal.println("  ${red("✗")} ${red("not bound: $error")}") }
        statuses.forEach { status ->
            val mark = if (status.available) green("●") else red("✗")
            val tools = gray("→ ${status.tools.joinToString()}")
            terminal.println("  $mark ${cyan(status.id)}  ${status.binary}  ${status.health}  $tools")
        }
    }

    /**
     * Output status as a formatted dashboard for human viewing.
     */
    private fun outputDashboard(
        threadsResult: Result<List<ThreadSummary>>,
        ticketsResult: Result<List<TicketSummary>>,
        verbose: Boolean,
        workspace: String?,
    ) {
        terminal.println(bold(cyan("⚡ AMPERE System Status")))
        terminal.println()

        // Agents section
        terminal.println(bold("🤖 Agents"))
        // TODO: Replace with actual agent status service when available
        // For now, show the three agents we know are created in Main.kt
        terminal.println("  ${green("●")} CodeWriterAgent       ${dim("idle")}      Last: 5m ago")
        terminal.println("  ${green("●")} ProductManagerAgent  ${dim("idle")}      Last: 3m ago")
        terminal.println("  ${green("●")} QualityAssuranceAgent ${dim("idle")}     Last: 1m ago")
        terminal.println()

        // Threads section
        terminal.println(bold("📝 Threads"))
        threadsResult.fold(
            onSuccess = { threads ->
                if (threads.isEmpty()) {
                    terminal.println(dim("  No active threads"))
                } else {
                    val escalated = threads.count { it.hasUnreadEscalations }
                    val stale = threads.count { isStale(it.lastActivity) && !it.hasUnreadEscalations }

                    terminal.println("  Total: ${green(threads.size.toString())} active")

                    if (escalated > 0) {
                        terminal.println("  ${red("⚠ $escalated")} with escalations")
                    }

                    if (stale > 0) {
                        terminal.println("  ${yellow("⏰ $stale")} stale (>24h since activity)")
                    }

                    // Verbose: show thread details
                    if (verbose && threads.isNotEmpty()) {
                        terminal.println()
                        terminal.println("  ${underline("Active threads:")}")
                        threads.take(5).forEach { thread ->
                            val indicator = if (thread.hasUnreadEscalations) red("⚠") else gray("●")
                            terminal.println("    $indicator ${thread.threadId.take(8)}: ${thread.title.take(40)}${if (thread.title.length > 40) "..." else ""}")
                            terminal.println("      ${gray("${thread.messageCount} messages, ${thread.participantIds.size} participants")}")
                        }
                    }
                }
            },
            onFailure = { error ->
                terminal.println(red("  Error: ${error.message}"))
            }
        )
        terminal.println()

        // Tickets section
        terminal.println(bold("🎫 Tickets"))
        ticketsResult.fold(
            onSuccess = { tickets ->
                if (tickets.isEmpty()) {
                    terminal.println(dim("  No active tickets"))
                } else {
                    val byStatus = tickets.groupBy { it.status }
                    val unassigned = tickets.count { it.assigneeId == null }
                    val highPriority = tickets.count { it.priority == "High" || it.priority == "Critical" }

                    terminal.println("  Total: ${green(tickets.size.toString())} active")

                    // Show breakdown by status
                    byStatus.entries.sortedByDescending { it.value.size }.forEach { (status, statusTickets) ->
                        val color = when (status) {
                            "InProgress" -> green
                            "Todo" -> cyan
                            "Blocked" -> red
                            else -> gray
                        }
                        terminal.println("    ${color(status)}: ${statusTickets.size}")
                    }

                    if (unassigned > 0) {
                        terminal.println("  ${yellow("⚠ $unassigned")} unassigned")
                    }

                    if (highPriority > 0) {
                        terminal.println("  ${red("🔥 $highPriority")} high priority")
                    }

                    // Verbose: show ticket details
                    if (verbose && tickets.isNotEmpty()) {
                        terminal.println()
                        terminal.println("  ${underline("Recent tickets:")}")
                        tickets.take(5).forEach { ticket ->
                            val statusColor = when (ticket.status) {
                                "InProgress" -> green
                                "Todo" -> cyan
                                "Blocked" -> red
                                else -> gray
                            }
                            terminal.println("    ${statusColor("●")} ${ticket.ticketId}: ${ticket.title.take(50)}${if (ticket.title.length > 50) "..." else ""}")
                            ticket.assigneeId?.let { assignee ->
                                terminal.println("      ${gray("Assigned to: $assignee")}")
                            }
                        }
                    }
                }
            },
            onFailure = { error ->
                terminal.println(red("  Error: ${error.message}"))
            }
        )
        terminal.println()

        // Summary section with any urgent items
        val urgentItems = mutableListOf<String>()

        threadsResult.getOrNull()?.let { threads ->
            val escalated = threads.filter { it.hasUnreadEscalations }
            if (escalated.isNotEmpty()) {
                urgentItems.add("${escalated.size} thread(s) need human attention")
            }
        }

        ticketsResult.getOrNull()?.let { tickets ->
            val blocked = tickets.filter { it.status == "Blocked" }
            if (blocked.isNotEmpty()) {
                urgentItems.add("${blocked.size} ticket(s) blocked")
            }
        }

        if (urgentItems.isNotEmpty()) {
            terminal.println(bold(red("⚠ Needs Attention:")))
            urgentItems.forEach { item ->
                terminal.println("  • $item")
            }
        } else {
            terminal.println(green("✓ All systems nominal"))
        }

        // Workspace section: the one directory agents may write to (AMPR-300)
        terminal.println()
        terminal.println(bold("📁 Workspace"))
        if (workspace != null) {
            terminal.println("  ${cyan(workspace)} ${gray("(agent writes confined here)")}")
        } else {
            terminal.println(dim("  No workspace pinned"))
        }
    }

    /** A configured harness as `ampere status` reports it. */
    internal data class HarnessStatus(
        val id: String,
        val binary: String,
        val tools: List<String>,
        val available: Boolean,
        /** `available`, `available (<version>)`, or `unavailable: <reason>`. */
        val health: String,
    ) {
        /** The line without colour: `<id>  <binary>  <health>  → <tools>`. */
        fun describe(): String = "$id  $binary  $health  → ${tools.joinToString()}"
    }

    companion object {
        /**
         * Asks every harness its health at once. A health check starts the agent and waits for it
         * to answer `initialize`, so each is a real launch; the context's `close()` ends them.
         */
        internal suspend fun harnessStatuses(harnesses: List<BoundHarness>): List<HarnessStatus> =
            coroutineScope {
                harnesses.map { bound ->
                    async {
                        val checked = bound.executor.performHealthCheck()
                        val health = checked.getOrNull()
                        val available = health?.isAvailable == true
                        val description = when {
                            available -> bound.executor.agentInfo?.version
                                ?.let { version -> "available ($version)" } ?: "available"
                            health != null -> "unavailable: ${health.issues.joinToString().ifBlank { "no reason given" }}"
                            else -> "unavailable: ${checked.exceptionOrNull()?.message ?: "the health check failed"}"
                        }
                        HarnessStatus(
                            id = bound.executor.id,
                            binary = bound.config.resolvedBinary.orEmpty(),
                            tools = bound.config.tools,
                            available = available,
                            health = description,
                        )
                    }
                }.awaitAll()
            }
    }

    /**
     * Check if a thread is stale (no activity in 24 hours).
     */
    private fun isStale(lastActivity: Instant): Boolean {
        val now = Clock.System.now()
        val threshold = 24.hours
        return (now - lastActivity) > threshold
    }
}
