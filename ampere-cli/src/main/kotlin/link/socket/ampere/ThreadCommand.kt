package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.repl.TerminalFactory

/**
 * Root command for thread operations. Doesn't do anything itself,
 * just serves as a container for list and show subcommands.
 */
class ThreadCommand(
    threadViewService: ThreadViewService,
    renderer: link.socket.ampere.renderer.CLIRenderer = link.socket.ampere.renderer.CLIRenderer(TerminalFactory.createTerminal())
) : CliktCommand(
    name = "thread",
    help = "View conversation threads in the environment"
) {
    init {
        subcommands(
            ThreadListCommand(threadViewService, renderer),
            ThreadShowCommand(threadViewService, renderer)
        )
    }

    override fun run() = Unit
}

/**
 * List all active threads in a table format.
 * Shows summary information: message counts, participants, last activity.
 */
class ThreadListCommand(
    private val threadViewService: ThreadViewService,
    private val renderer: link.socket.ampere.renderer.CLIRenderer
) : CliktCommand(
    name = "list",
    help = "List all active threads"
) {
    override fun run() = runBlocking {
        val result = threadViewService.listActiveThreads()

        result.fold(
            onSuccess = { threads ->
                val threadItems = threads.map { thread ->
                    link.socket.ampere.renderer.CLIRenderer.ThreadListItem(
                        threadId = thread.threadId,
                        title = thread.title,
                        messageCount = thread.messageCount,
                        participantCount = thread.participantIds.size,
                        lastActivity = thread.lastActivity,
                        hasUnreadEscalations = thread.hasUnreadEscalations
                    )
                }
                renderer.renderThreadList(threadItems)
            },
            onFailure = { error ->
                renderer.renderError(error.message ?: "Unknown error")
            }
        )
    }
}

/**
 * Show the complete conversation history for a specific thread.
 * Displays all messages with timestamps and speaker identification.
 */
class ThreadShowCommand(
    private val threadViewService: ThreadViewService,
    private val renderer: link.socket.ampere.renderer.CLIRenderer
) : CliktCommand(
    name = "show",
    help = "Display full thread conversation"
) {
    private val threadId by argument(name = "thread-id", help = "ID of the thread to display")

    override fun run() = runBlocking {
        val result = threadViewService.getThreadDetail(threadId)

        result.fold(
            onSuccess = { thread ->
                val threadDetail = link.socket.ampere.renderer.CLIRenderer.ThreadDetail(
                    threadId = threadId,
                    title = thread.title,
                    participants = thread.participants,
                    messages = thread.messages.map { message ->
                        link.socket.ampere.renderer.CLIRenderer.ThreadMessage(
                            sender = message.sender,
                            timestamp = message.timestamp,
                            content = message.content
                        )
                    }
                )
                renderer.renderThreadDetail(threadDetail)
            },
            onFailure = { error ->
                renderer.renderThreadNotFound(threadId)
            }
        )
    }
}
