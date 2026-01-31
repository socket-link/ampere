package link.socket.ampere.repl

import link.socket.ampere.AmpereContext
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

/**
 * Tab completion for AMPERE CLI commands.
 *
 * Provides context-aware suggestions for command names, subcommands,
 * and arguments like ticket IDs and agent IDs.
 */
class AmpereCompleter(
    private val context: AmpereContext
) : Completer {

    private val rootCommands = listOf(
        "help", "exit", "quit",
        "watch", "status",
        "thread", "outcomes",
        "ticket", "message", "agent",
        "clear"
    )

    private val ticketSubcommands = listOf("create", "assign", "status", "list")
    private val messageSubcommands = listOf("post", "create-thread")
    private val agentSubcommands = listOf("wake", "list")
    private val threadSubcommands = listOf("list", "show")
    private val outcomesSubcommands = listOf("ticket", "search", "executor", "stats")

    override fun complete(
        reader: LineReader,
        line: ParsedLine,
        candidates: MutableList<Candidate>
    ) {
        val words = line.words()

        when {
            words.isEmpty() || words.size == 1 -> {
                // Complete root command
                completeCommand(line.word(), candidates, rootCommands)
            }
            words.size == 2 -> {
                // Complete subcommand based on first word
                when (words[0].lowercase()) {
                    "ticket" -> completeCommand(line.word(), candidates, ticketSubcommands)
                    "message" -> completeCommand(line.word(), candidates, messageSubcommands)
                    "agent" -> completeCommand(line.word(), candidates, agentSubcommands)
                    "thread" -> completeCommand(line.word(), candidates, threadSubcommands)
                    "outcomes" -> completeCommand(line.word(), candidates, outcomesSubcommands)
                }
            }
            words.size > 2 -> {
                // Complete arguments (IDs, etc.)
                when {
                    words[0] == "ticket" && words[1] == "assign" && words.size == 3 -> {
                        // Complete ticket ID
                        completeTicketIds(line.word(), candidates)
                    }
                    words[0] == "agent" && words[1] == "wake" -> {
                        // Complete agent ID
                        completeAgentIds(line.word(), candidates)
                    }
                    words[0] == "message" && words[1] == "post" && words.size == 3 -> {
                        // Complete thread ID
                        completeThreadIds(line.word(), candidates)
                    }
                    words[0] == "thread" && words[1] == "show" -> {
                        // Complete thread ID
                        completeThreadIds(line.word(), candidates)
                    }
                }
            }
        }
    }

    private fun completeCommand(
        partial: String,
        candidates: MutableList<Candidate>,
        options: List<String>
    ) {
        options
            .filter { it.startsWith(partial, ignoreCase = true) }
            .forEach { candidates.add(Candidate(it)) }
    }

    private fun completeTicketIds(partial: String, candidates: MutableList<Candidate>) {
        // TODO: Query database for ticket IDs matching partial
        // For now, just add placeholder hint
        if (partial.isEmpty()) {
            candidates.add(Candidate("ticket-", "ticket-", null, "Ticket ID", null, null, false))
        }
    }

    private fun completeAgentIds(partial: String, candidates: MutableList<Candidate>) {
        // Known agent IDs from bundled agents
        val knownAgents = listOf(
            "agent-pm", "agent-dev", "agent-reviewer",
            "agent-code", "agent-docs", "agent-test"
        )
        completeCommand(partial, candidates, knownAgents)
    }

    private fun completeThreadIds(partial: String, candidates: MutableList<Candidate>) {
        // TODO: Query database for thread IDs
        if (partial.isEmpty()) {
            candidates.add(Candidate("thread-", "thread-", null, "Thread ID", null, null, false))
        }
    }
}
