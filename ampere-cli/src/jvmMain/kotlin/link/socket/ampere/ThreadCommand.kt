package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.data.DEFAULT_JSON

/**
 * Root command for thread operations. Doesn't do anything itself,
 * just serves as a container for list and show subcommands.
 */
class ThreadCommand(
    threadViewService: ThreadViewService
) : CliktCommand(
    name = "thread",
    help = "View conversation threads in the substrate"
) {
    init {
        subcommands(
            ThreadListCommand(threadViewService),
            ThreadShowCommand(threadViewService)
        )
    }

    override fun run() = Unit
}

/**
 * List all active threads in a table format.
 * Shows summary information: message counts, participants, last activity.
 */
class ThreadListCommand(
    private val threadViewService: ThreadViewService
) : CliktCommand(
    name = "list",
    help = "List all active threads"
) {
    private val jsonOutput by option("--json", "-j", help = "Output as JSON").flag()

    private val terminal = Terminal()

    override fun run() = runBlocking {
        val result = threadViewService.listActiveThreads()

        result.fold(
            onSuccess = { threads ->
                if (jsonOutput) {
                    // JSON output mode
                    terminal.println(DEFAULT_JSON.encodeToString(threads))
                } else {
                    // Human-readable table output
                    if (threads.isEmpty()) {
                        terminal.println(dim("No active threads found"))
                    } else {
                        terminal.println(bold("Active Threads"))
                        terminal.println()

                        val table = table {
                            header {
                                row("Thread ID", "Title", "Messages", "Participants", "Last Activity", "Status")
                            }
                            body {
                                threads.forEach { thread ->
                                    row(
                                        gray(thread.threadId),
                                        cyan(thread.title),
                                        thread.messageCount.toString(),
                                        thread.participantIds.size.toString(),
                                        formatTimestamp(thread.lastActivity),
                                        if (thread.hasUnreadEscalations) {
                                            red("⚠ ESCALATED")
                                        } else {
                                            green("Active")
                                        }
                                    )
                                }
                            }
                        }

                        terminal.println(table)
                        terminal.println()
                        terminal.println(dim("Total: ${threads.size} active thread(s)"))
                    }
                }
            },
            onFailure = { error ->
                terminal.println(red("Error: ${error.message}"))
            }
        )
    }

    private fun formatTimestamp(instant: kotlinx.datetime.Instant): String {
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.date} ${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}"
    }
}

/**
 * Show the complete conversation history for a specific thread.
 * Displays all messages with timestamps and speaker identification.
 */
class ThreadShowCommand(
    private val threadViewService: ThreadViewService
) : CliktCommand(
    name = "show",
    help = "Display full thread conversation"
) {
    private val threadId by argument(name = "thread-id", help = "ID of the thread to display")

    private val jsonOutput by option("--json", "-j", help = "Output as JSON").flag()

    private val terminal = Terminal()

    override fun run() = runBlocking {
        val result = threadViewService.getThreadDetail(threadId)

        result.fold(
            onSuccess = { thread ->
                if (jsonOutput) {
                    // JSON output mode
                    terminal.println(DEFAULT_JSON.encodeToString(thread))
                } else {
                    // Human-readable output
                    terminal.println(bold(cyan("Thread: ${thread.title}")))
                    terminal.println(dim("Thread ID: $threadId"))
                    terminal.println(dim("Participants: ${thread.participants.joinToString(", ")}"))
                    terminal.println()

                    if (thread.messages.isEmpty()) {
                        terminal.println(dim("No messages in this thread"))
                    } else {
                        thread.messages.forEach { message ->
                            val senderColor = when (message.sender) {
                                is MessageSender.Agent -> blue
                                is MessageSender.Human -> green
                            }

                            terminal.println(
                                bold(senderColor("${message.sender.getIdentifier()}")) +
                                    " " +
                                    dim("at ${formatTimestamp(message.timestamp)}")
                            )
                            terminal.println(message.content)
                            terminal.println()
                        }

                        terminal.println(dim("Total: ${thread.messages.size} message(s)"))
                    }
                }
            },
            onFailure = { error ->
                terminal.println(red("Error: Thread '$threadId' not found"))
                terminal.println(yellow("Tip: Use 'ampere thread list' to see available threads"))
            }
        )
    }

    private fun formatTimestamp(instant: kotlinx.datetime.Instant): String {
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.date} ${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}:${localDateTime.second.toString().padStart(2, '0')}"
    }
}
